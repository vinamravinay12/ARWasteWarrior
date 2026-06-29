import json
import os
import re
import time
import hashlib
from decimal import Decimal

import boto3
from botocore.config import Config

REGION = os.environ.get("AWS_REGION", "ap-south-1")
MODEL_ID = os.getenv("MODEL_ID", "global.amazon.nova-lite-v1:0")
SCENE_CACHE_TABLE = os.environ.get("SCENE_CACHE_TABLE", "SceneEncounterCache")
SCENE_STATE_TABLE = os.environ.get("SCENE_STATE_TABLE", "SceneEncounterState")

BEDROCK_TIMEOUT_CONNECT = float(os.getenv("BEDROCK_CONNECT_TIMEOUT", "1.0"))
BEDROCK_TIMEOUT_READ = float(os.getenv("BEDROCK_READ_TIMEOUT", "8.0"))

_client = boto3.client(
    "bedrock-runtime",
    region_name=REGION,
    config=Config(
        connect_timeout=BEDROCK_TIMEOUT_CONNECT,
        read_timeout=BEDROCK_TIMEOUT_READ,
        retries={"max_attempts": 1},
    ),
)
_dynamo = boto3.resource("dynamodb", region_name=REGION)
_cache_table = _dynamo.Table(SCENE_CACHE_TABLE)
_state_table = _dynamo.Table(SCENE_STATE_TABLE)

CATEGORY_MAP = {
    "PLASTIC": "PLASTIC",
    "PAPER": "PAPER",
    "METAL": "METAL",
    "GLASS": "GLASS",
    "ORGANIC": "ORGANIC",
    "E_WASTE": "E_WASTE",
    "EWASTE": "E_WASTE",
    "aE-WASTE": "E_WASTE",
    "UNKNOWN": "UNKNOWN",
    "NOT_GARBAGE": "UNKNOWN",
    "NOT GARBAGE": "UNKNOWN",
}

BIN_MAP = {
    "PLASTIC": "PLASTIC",
    "PAPER": "PAPER",
    "METAL": "METAL",
    "GLASS": "GLASS",
    "ORGANIC": "ORGANIC",
    "E_WASTE": "E_WASTE",
    "EWASTE": "E_WASTE",
    "GENERAL": "GENERAL",
    "UNKNOWN": "UNKNOWN",
}

SIZE_VALUES = {"SMALL", "MEDIUM", "LARGE"}

WASTE_HINTS = {
    "garbage",
    "trash",
    "waste",
    "litter",
    "plastic",
    "bottle",
    "wrapper",
    "packet",
    "paper",
    "cardboard",
    "can",
    "tin",
    "food",
    "organic",
    "peel",
}

NON_WASTE_HINTS = {
    "wall",
    "floor",
    "ceiling",
    "table",
    "desk",
    "chair",
    "bed",
    "sofa",
    "screen",
    "monitor",
    "keyboard",
    "laptop",
    "person",
    "face",
    "hand",
    "arm",
}

BIN_HINTS = {
    "bin",
    "dustbin",
    "dust bin",
    "trash can",
    "garbage can",
    "trash bin",
    "garbage bin",
    "recycle",
    "recycle bin",
    "waste bin",
    "waste basket",
    "bucket",
    "container",
    "wet waste",
    "dry waste",
    "compost",
    "compostable",
}

_LABEL_SANITIZE_RE = re.compile(r"[^\w\s\-\./]")


def _resp(body: dict, cache_header: str = None, status: int = 200):
    headers = {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
    }
    if cache_header:
        headers["X-Cache"] = cache_header
    return {"statusCode": status, "headers": headers, "body": json.dumps(body, ensure_ascii=False)}


def _payload(event):
    if not isinstance(event, dict):
        return {}
    body = event.get("body")
    if body is None:
        return event
    if isinstance(body, str):
        try:
            return json.loads(body)
        except Exception:
            return {}
    if isinstance(body, dict):
        return body
    return {}


def _as_bool(value, default=False):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float, Decimal)):
        return value != 0
    if isinstance(value, str):
        v = value.strip().lower()
        if v in {"true", "1", "yes", "y"}:
            return True
        if v in {"false", "0", "no", "n"}:
            return False
    return default


def _as_int(value, default=0):
    try:
        if isinstance(value, Decimal):
            return int(value)
        return int(value)
    except Exception:
        return default


def _safe_labels(raw_labels):
    if not isinstance(raw_labels, list):
        return []
    out = []
    for item in raw_labels[:16]:
        s = _LABEL_SANITIZE_RE.sub("", str(item).strip().lower())
        if s:
            out.append(s[:60])
    return out


def _norm_category(value):
    s = str(value or "").strip().upper().replace(" ", "_").replace("-", "_")
    return CATEGORY_MAP.get(s, "UNKNOWN")


def _norm_bin(value):
    s = str(value or "").strip().upper().replace(" ", "_").replace("-", "_")
    return BIN_MAP.get(s, "UNKNOWN")


def _norm_size(value):
    s = str(value or "").strip().upper()
    return s if s in SIZE_VALUES else "MEDIUM"


def _stable_hash(parts):
    raw = "|".join(str(x) for x in parts)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def _scene_hash(payload, category, labels):
    incoming = str(payload.get("sceneHash") or "").strip()
    if incoming:
        return incoming[:32]
    return _stable_hash(["SCENE", category, " ".join(labels[:12])])


def _bin_scene_hash(payload, labels, detected_bins):
    incoming = str(payload.get("binSceneHash") or "").strip()
    if incoming:
        return incoming[:32]
    bins = []
    if isinstance(detected_bins, list):
        for b in detected_bins[:8]:
            if isinstance(b, dict):
                bins.append(f"{_norm_bin(b.get('type'))}:{_as_int(b.get('confidence'), 0)}")
    return _stable_hash(["BIN", " ".join(labels[:12]), "|".join(sorted(bins))])


def _contains_any(text, words):
    return any(w in text for w in words)


def _is_likely_garbage(category, likely_garbage_flag, labels):
    joined = " ".join(labels)
    has_waste_hint = _contains_any(joined, WASTE_HINTS)
    has_non_waste_hint = _contains_any(joined, NON_WASTE_HINTS)

    if category != "UNKNOWN":
        return True
    # Trust client signal: if the app's local ML says likely garbage, let Bedrock decide.
    # Do not block on non-waste hints alone since waste can appear alongside furniture/floor.
    if likely_garbage_flag:
        return True
    if has_waste_hint and not has_non_waste_hint:
        return True
    return False


def _default_bin_type(category):
    return {
        "PLASTIC": "DRY",
        "PAPER": "DRY",
        "METAL": "DRY",
        "GLASS": "DRY",
        "ORGANIC": "WET",
        "E_WASTE": "E_WASTE",
    }.get(category, "GENERAL")


def _default_demon_count(category, size, bin_closed, bin_overflowing):
    base = {
        "E_WASTE": 4,
        "PLASTIC": 3,
        "METAL": 3,
        "ORGANIC": 2,
        "PAPER": 2,
        "GLASS": 2,
    }.get(category, 2)
    size_bonus = {"SMALL": 0, "MEDIUM": 1, "LARGE": 2}.get(size, 1)
    penalty = 0
    if _as_bool(bin_closed, False):
        penalty += 1
    if _as_bool(bin_overflowing, False):
        penalty += 1
    return max(1, min(8, base + size_bonus + penalty))


def _recommended_destroy_count(remaining, pending=0):
    if pending > 0:
        return max(1, min(3, pending, remaining))
    return max(1, min(3, remaining)) if remaining > 0 else 0


def _cache_get(scene_hash):
    try:
        r = _cache_table.get_item(Key={"sceneHash": scene_hash})
        item = r.get("Item")
        if not item:
            return None
        body = json.loads(item.get("data") or "{}")
        return body
    except Exception as e:
        print(f"CACHE_READ_ERR[{scene_hash}]: {e}")
        return None


def _cache_put(scene_hash, data, ttl_sec=86400):
    try:
        _cache_table.put_item(
            Item={
                "sceneHash": scene_hash,
                "data": json.dumps(data, ensure_ascii=False),
                "ttl": int(time.time()) + int(ttl_sec),
            }
        )
    except Exception as e:
        print(f"CACHE_WRITE_ERR[{scene_hash}]: {e}")


# ---------------------------------------------------------------------------
# Perceptual frame-hash helpers (dHash-based, LSH approximate matching)
# ---------------------------------------------------------------------------

# Maximum Hamming distance between two 64-bit dHashes that we still treat as
# the "same scene".  dHash typical ranges:
#   - Same frame, minor sensor noise : 0–2 bits
#   - Same scene, slight hand shake  : 2–6 bits
#   - Same scene, lighting change    : 5–12 bits
#   - Different scene               : 20–64 bits
FRAME_HASH_HAMMING_THRESHOLD = 12


def _hamming_distance(hex1: str, hex2: str) -> int:
    """Bit-count of XOR between two hex-encoded 64-bit hashes."""
    try:
        return bin(int(hex1, 16) ^ int(hex2, 16)).count("1")
    except Exception:
        return 64


def _lsh_keys(frame_hash: str) -> list:
    """
    Split the 64-bit frame hash into 8 bands of 8 bits each.
    Returns 8 DynamoDB keys of the form  LSH#{band}#{byte_hex}.

    Guarantee: if two hashes differ in ≤ 7 bits, at least one band
    is guaranteed to be identical (pigeonhole over 8 bands × 8 bits).
    For diffs up to 12 bits the probability of at least one band
    matching is ~94 %, making LSH a reliable first-pass filter.
    """
    try:
        h = int(frame_hash, 16)
        return [f"LSH#{b}#{(h >> (b * 8)) & 0xFF:02x}" for b in range(8)]
    except Exception:
        return []


def _lsh_lookup(frame_hash: str) -> str | None:
    """
    BatchGetItem across all 8 LSH band keys.
    Returns the stored frame_hash of a similar cached scene, or None.
    Verifies Hamming distance before returning to avoid false positives.
    """
    keys = _lsh_keys(frame_hash)
    if not keys:
        return None
    try:
        resp = _dynamo.batch_get_item(
            RequestItems={
                SCENE_CACHE_TABLE: {"Keys": [{"sceneHash": k} for k in keys]}
            }
        )
        items = resp.get("Responses", {}).get(SCENE_CACHE_TABLE, [])
        best_hash = None
        best_dist = FRAME_HASH_HAMMING_THRESHOLD + 1
        for item in items:
            data = json.loads(item.get("data") or "{}")
            candidate = data.get("frameHash", "")
            if not candidate:
                continue
            dist = _hamming_distance(frame_hash, candidate)
            if dist < best_dist:
                best_dist = dist
                best_hash = candidate
        return best_hash  # None if nothing within threshold
    except Exception as e:
        print(f"LSH_LOOKUP_ERR[{frame_hash}]: {e}")
        return None


def _lsh_store(frame_hash: str, ttl_sec: int = 86400) -> None:
    """
    Write 8 LSH index entries pointing to frame_hash.
    Each entry's data is a tiny JSON pointer: {"frameHash": "..."}.
    On collision a newer scene overwrites the pointer; the Hamming
    check in _lsh_lookup prevents returning a wrong result.
    """
    keys = _lsh_keys(frame_hash)
    pointer = json.dumps({"frameHash": frame_hash})
    expiry = int(time.time()) + ttl_sec
    for key in keys:
        try:
            _cache_table.put_item(
                Item={"sceneHash": key, "data": pointer, "ttl": expiry}
            )
        except Exception as e:
            print(f"LSH_STORE_ERR[{key}]: {e}")


def _state_get(scene_hash):
    try:
        r = _state_table.get_item(Key={"sceneHash": scene_hash})
        item = r.get("Item") or {}
        return {
            "sceneHash": scene_hash,
            "remainingDemons": _as_int(item.get("remainingDemons"), 0),
            "pendingPickupCount": _as_int(item.get("pendingPickupCount"), 0),
            "pickupFailCount": _as_int(item.get("pickupFailCount"), 0),
            "throwFailCount": _as_int(item.get("throwFailCount"), 0),
            "category": _norm_category(item.get("category")),
            "lastLabels": item.get("lastLabels") if isinstance(item.get("lastLabels"), list) else [],
            "lastBinSceneHash": str(item.get("lastBinSceneHash") or ""),
        }
    except Exception as e:
        print(f"STATE_READ_ERR[{scene_hash}]: {e}")
        return {
            "sceneHash": scene_hash,
            "remainingDemons": 0,
            "pendingPickupCount": 0,
            "pickupFailCount": 0,
            "throwFailCount": 0,
            "category": "UNKNOWN",
            "lastLabels": [],
            "lastBinSceneHash": "",
        }


def _state_put(
    scene_hash,
    remaining,
    pending,
    category,
    labels=None,
    last_bin_scene_hash="",
    pickup_fail_count=0,
    throw_fail_count=0,
    ttl_sec=86400,
):
    try:
        item = {
            "sceneHash": scene_hash,
            "remainingDemons": int(max(0, remaining)),
            "pendingPickupCount": int(max(0, pending)),
            "pickupFailCount": int(max(0, pickup_fail_count)),
            "throwFailCount": int(max(0, throw_fail_count)),
            "category": _norm_category(category),
            "ttl": int(time.time()) + int(ttl_sec),
        }
        if isinstance(labels, list):
            item["lastLabels"] = [str(x)[:60] for x in labels[:16]]
        if last_bin_scene_hash:
            item["lastBinSceneHash"] = last_bin_scene_hash[:32]
        _state_table.put_item(Item=item)
    except Exception as e:
        print(f"STATE_WRITE_ERR[{scene_hash}]: {e}")


def _invoke_bedrock_json(prompt, max_tokens=350):
    try:
        resp = _client.converse(
            modelId=MODEL_ID,
            messages=[{"role": "user", "content": [{"text": prompt}]}],
            inferenceConfig={"temperature": 0.0, "maxTokens": max_tokens},
        )
        content = resp.get("output", {}).get("message", {}).get("content", [])
        raw = "\n".join(block.get("text", "") for block in content if isinstance(block, dict) and "text" in block).strip()
        clean = raw.replace("```json", "").replace("```", "").strip()
        start = clean.find("{")
        end = clean.rfind("}")
        if start == -1 or end == -1 or end < start:
            return None
        return json.loads(clean[start:end + 1])
    except Exception as e:
        print(f"BEDROCK_ERR: {e}")
        return None


def _scan_non_garbage_response(scene_hash, language):
    return {
        "isGarbage": False,
        "resolvedCategory": None,
        "binType": "GENERAL",
        "tip": "This appears to be a normal object, not garbage.",
        "tipHindi": "यह सामान्य वस्तु लग रही है, कचरा नहीं।",
        "binIssue": "No waste action needed.",
        "binIdentificationTipEnglish": "",
        "binIdentificationTipHindi": "",
        "hazardInfoEnglish": "",
        "hazardInfoHindi": "",
        "actionPrompt": "No mission. Find real garbage to start.",
        "demonType": "PLASTIC",
        "demonCount": 0,
        "demonMix": [],
        "diseaseWarningHindi": "यह सामान्य वस्तु लग रही है, कचरा नहीं।",
        "diseaseWarningEnglish": "This appears to be a normal object, not garbage.",
        "speechTextHindi": "यह कचरा नहीं है। कृपया वास्तविक कचरे पर कैमरा रखें।",
        "speechTextEnglish": "This is not garbage. Point the camera at actual waste.",
        "sceneHash": scene_hash,
        "remainingDemons": 0,
        "recommendedDestroyCount": 0,
        "pendingPickupCount": 0,
        "seenBefore": False,
        "source": "BEDROCK",
    }


def _scan_handler(p):
    language = str(p.get("language") or "en").strip().lower()[:5]
    category = _norm_category(p.get("category"))
    confidence = _as_int(p.get("confidence"), 50)
    likely_garbage = _as_bool(p.get("likelyGarbage"), category != "UNKNOWN")
    size = _norm_size(p.get("garbageSize"))
    bin_closed = p.get("binClosed")
    bin_overflowing = p.get("binOverflowing")
    labels = _safe_labels(p.get("rawLabels"))
    frame_hash = str(p.get("frameHash") or "").strip()[:16]

    scene_hash = _scene_hash(p, category, labels)

    # Frame-hash cache: approximate perceptual match via LSH + Hamming distance.
    # LSH bands give a ~94 % recall for scenes within 12-bit Hamming distance;
    # the Hamming check then rejects any false-positive band collisions.
    # This handles the same physical scene across sessions even with minor
    # lighting/angle changes, where the label-based sceneHash would miss.
    if frame_hash:
        similar_hash = _lsh_lookup(frame_hash)
        if similar_hash:
            frame_cached = _cache_get(f"FRAME#{similar_hash}")
            if frame_cached is not None:
                state = _state_get(scene_hash)
                frame_cached["seenBefore"] = True
                frame_cached["source"] = "CACHE"
                frame_cached["sceneHash"] = scene_hash
                if frame_cached.get("isGarbage"):
                    frame_cached["remainingDemons"] = state["remainingDemons"]
                    frame_cached["pendingPickupCount"] = state["pendingPickupCount"]
                    frame_cached["recommendedDestroyCount"] = _recommended_destroy_count(
                        state["remainingDemons"], state["pendingPickupCount"]
                    )
                return _resp(frame_cached, cache_header="FRAME_HIT")

    cached = _cache_get(scene_hash)
    if cached is not None:
        state = _state_get(scene_hash)
        cached["seenBefore"] = True
        cached["source"] = "CACHE"
        if cached.get("isGarbage"):
            cached["remainingDemons"] = state["remainingDemons"]
            cached["pendingPickupCount"] = state["pendingPickupCount"]
            cached["recommendedDestroyCount"] = _recommended_destroy_count(
                state["remainingDemons"], state["pendingPickupCount"]
            )
        cached["sceneHash"] = scene_hash
        return _resp(cached, cache_header="HIT")

    if not _is_likely_garbage(category, likely_garbage, labels):
        out = _scan_non_garbage_response(scene_hash, language)
        _cache_put(scene_hash, out, ttl_sec=3600)
        if frame_hash:
            _cache_put(f"FRAME#{frame_hash}", out, ttl_sec=3600)
            _lsh_store(frame_hash, ttl_sec=3600)
        return _resp(out)

    prompt = f"""Return ONLY one valid JSON object.

Keys:
- isGarbage: boolean
- resolvedCategory: "PLASTIC"|"PAPER"|"METAL"|"GLASS"|"ORGANIC"|"E_WASTE"|null
- binType: "DRY"|"WET"|"E_WASTE"|"BIOHAZARD"|"GENERAL"
- tip: string (English educational tip about this specific waste type)
- tipHindi: string (same tip in Hindi)
- binIssue: string
- binIdentificationTipEnglish: string (how to identify the correct bin by color/label/marking)
- binIdentificationTipHindi: string (same in Hindi)
- hazardInfoEnglish: string (specific health or environment risk if this waste is left on road/floor)
- hazardInfoHindi: string (same in Hindi)
- actionPrompt: string
- demonType: "PLASTIC"|"ORGANIC"|"E_WASTE"
- demonCount: integer 0..8
- demonMix: array
- diseaseWarningHindi: string
- diseaseWarningEnglish: string
- speechTextHindi: string (2-3 sentences in Hindi covering: waste type identified + correct bin name + how to identify that bin + one key hazard of leaving it on road)
- speechTextEnglish: string (same 2-3 sentences in English)

Input:
category={category}
confidence={confidence}
likelyGarbage={likely_garbage}
garbageSize={size}
binClosed={bin_closed}
binOverflowing={bin_overflowing}
rawLabels={json.dumps(labels, ensure_ascii=False)}

Rules:
- If object is not garbage => isGarbage=false, resolvedCategory=null, demonCount=0, demonMix=[]
- If garbage => demonCount must be >=1
- binType: DRY for plastic/paper/metal/glass (recyclables); WET for organic/food; E_WASTE for electronics/batteries; BIOHAZARD for medical/sanitary; GENERAL for mixed/unknown
- binIdentificationTipEnglish example: "Look for a blue bin labeled Dry Waste or Recyclables. For food waste, use the green bin labeled Wet Waste or Compost. For electronics, use the bin with the e-waste symbol at authorized collection points."
- hazardInfoEnglish: be specific e.g. "Plastic left on roads blocks drains causing floods, and microplastics leach into soil harming ecosystems and entering the food chain."
- speechTextEnglish must include (1) waste type detected, (2) which bin and how to recognize it by color/label, (3) key hazard of leaving it on floor/road
"""

    model = _invoke_bedrock_json(prompt, max_tokens=700)
    if not isinstance(model, dict):
        out = _scan_non_garbage_response(scene_hash, language)
        out["source"] = "ERROR"
        return _resp(out)

    is_garbage = _as_bool(model.get("isGarbage"), True)
    resolved = _norm_category(model.get("resolvedCategory"))
    if resolved == "UNKNOWN" and category != "UNKNOWN":
        resolved = category

    if (not is_garbage) or resolved == "UNKNOWN":
        out = _scan_non_garbage_response(scene_hash, language)
        _cache_put(scene_hash, out, ttl_sec=3600)
        if frame_hash:
            _cache_put(f"FRAME#{frame_hash}", out, ttl_sec=3600)
            _lsh_store(frame_hash, ttl_sec=3600)
        return _resp(out)

    default_count = _default_demon_count(resolved, size, bin_closed, bin_overflowing)
    label_bonus = 1 if len(labels) >= 4 else 0
    confidence_bonus = 1 if confidence >= 75 else 0
    stable_min_count = max(1, min(8, default_count + label_bonus + confidence_bonus))
    model_count = _as_int(model.get("demonCount"), default_count)
    demon_count = max(1, min(8, model_count))
    # Prevent under-counting from conservative model output.
    if demon_count < stable_min_count:
        demon_count = stable_min_count

    demon_type = str(model.get("demonType") or "").strip().upper().replace("-", "_")
    if demon_type not in {"PLASTIC", "ORGANIC", "E_WASTE"}:
        demon_type = "E_WASTE" if resolved == "E_WASTE" else ("ORGANIC" if resolved == "ORGANIC" else "PLASTIC")

    tip = str(model.get("tip") or "").strip() or f"Put {resolved} waste in the correct bin."
    bin_issue = str(model.get("binIssue") or "").strip() or "Check nearby bin condition."
    action_prompt = str(model.get("actionPrompt") or "").strip() or "Pick garbage, verify pickup, then throw in bin."

    out = {
        "isGarbage": True,
        "resolvedCategory": resolved,
        "binType": str(model.get("binType") or _default_bin_type(resolved)),
        "tip": tip,
        "tipHindi": str(model.get("tipHindi") or tip),
        "binIssue": bin_issue,
        "binIdentificationTipEnglish": str(model.get("binIdentificationTipEnglish") or ""),
        "binIdentificationTipHindi": str(model.get("binIdentificationTipHindi") or ""),
        "hazardInfoEnglish": str(model.get("hazardInfoEnglish") or ""),
        "hazardInfoHindi": str(model.get("hazardInfoHindi") or ""),
        "actionPrompt": action_prompt,
        "demonType": demon_type,
        "demonCount": demon_count,
        "demonMix": model.get("demonMix") if isinstance(model.get("demonMix"), list) else [],
        "diseaseWarningHindi": str(model.get("diseaseWarningHindi") or "कचरे का गलत निपटान बीमारियों का खतरा बढ़ाता है।"),
        "diseaseWarningEnglish": str(model.get("diseaseWarningEnglish") or "Improper waste disposal increases disease risk."),
        "speechTextHindi": str(model.get("speechTextHindi") or "कचरा उठाइए और सही डस्टबिन में डालिए।"),
        "speechTextEnglish": str(model.get("speechTextEnglish") or "Garbage detected. Please dispose of it in the correct bin."),
        "sceneHash": scene_hash,
        "remainingDemons": demon_count,
        "recommendedDestroyCount": _recommended_destroy_count(demon_count, 0),
        "pendingPickupCount": 0,
        "seenBefore": False,
        "source": "BEDROCK",
    }

    _cache_put(scene_hash, out, ttl_sec=86400)
    if frame_hash:
        _cache_put(f"FRAME#{frame_hash}", out, ttl_sec=86400)
        _lsh_store(frame_hash, ttl_sec=86400)
    _state_put(
        scene_hash=scene_hash,
        remaining=demon_count,
        pending=0,
        pickup_fail_count=0,
        throw_fail_count=0,
        category=resolved,
        labels=labels,
        ttl_sec=86400,
    )
    return _resp(out)


def _bin_scan_handler(p):
    language = str(p.get("language") or "en").strip().lower()[:5]
    labels = _safe_labels(p.get("rawLabels"))
    detected_bins = p.get("detectedBins") if isinstance(p.get("detectedBins"), list) else []
    bin_hash = _bin_scene_hash(p, labels, detected_bins)
    cache_key = f"BIN#{bin_hash}"

    cached = _cache_get(cache_key)
    if cached is not None:
        cached["seenBefore"] = True
        cached["source"] = "CACHE"
        cached["binSceneHash"] = bin_hash
        return _resp(cached, cache_header="HIT")

    bin_detected = False
    bin_type = "UNKNOWN"
    best_conf = -1
    for b in detected_bins[:8]:
        if not isinstance(b, dict):
            continue
        conf = _as_int(b.get("confidence"), 0)
        t = _norm_bin(b.get("type"))
        if conf > best_conf:
            best_conf = conf
            bin_type = t
        if conf >= 35 and t != "UNKNOWN":
            bin_detected = True

    joined = " ".join(labels)
    if not bin_detected and _contains_any(joined, BIN_HINTS):
        bin_detected = True
        if bin_type == "UNKNOWN":
            bin_type = "GENERAL"

    if not bin_detected:
        prompt = f"""Return ONLY one JSON object with keys: binDetected(boolean), binType(string one of PLASTIC,PAPER,METAL,GLASS,ORGANIC,E_WASTE,GENERAL,UNKNOWN), message(string), speechTextEnglish(string), speechTextHindi(string).
labels={json.dumps(labels, ensure_ascii=False)}
detectedBins={json.dumps(detected_bins[:6], ensure_ascii=False)}
"""
        model = _invoke_bedrock_json(prompt, max_tokens=180)
        if isinstance(model, dict):
            bin_detected = _as_bool(model.get("binDetected"), False)
            bin_type = _norm_bin(model.get("binType"))

    if not bin_detected:
        # Gameplay fallback: during active cleanup, avoid dead-end on bin scan.
        # If label hints look bin-like, confirm as GENERAL bin.
        state_hint = _state_get(bin_hash)
        pending_pickup = _as_int(state_hint.get("pendingPickupCount"), 0)
        strong_bin_tokens = {
            "bin",
            "dustbin",
            "dust",
            "trash",
            "garbage",
            "recycle",
            "waste",
            "bucket",
            "container",
            "basket",
            "wet",
            "dry",
        }
        has_token_hint = any(tok in joined for tok in strong_bin_tokens)
        if has_token_hint and (pending_pickup > 0 or len(labels) >= 2):
            bin_detected = True
            if bin_type == "UNKNOWN":
                bin_type = "GENERAL"

    message = "Bin detected." if bin_detected else "Bin not detected. Point camera at dustbin."
    if language == "hi":
        message = "डस्टबिन मिल गया।" if bin_detected else "डस्टबिन नहीं मिला, कैमरा डस्टबिन पर रखें।"

    out = {
        "binDetected": bool(bin_detected),
        "binType": bin_type,
        "binClosed": p.get("binClosed"),
        "binOverflowing": p.get("binOverflowing"),
        "message": message,
        "speechTextEnglish": "Bin detected." if bin_detected else "Please point camera at a garbage bin.",
        "speechTextHindi": "डस्टबिन मिल गया।" if bin_detected else "कृपया डस्टबिन पर कैमरा रखें।",
        "binSceneHash": bin_hash,
        "seenBefore": False,
        "source": "BEDROCK" if bin_detected else "ERROR",
    }

    _cache_put(cache_key, out, ttl_sec=86400)
    return _resp(out)


def _pickup_handler(p):
    language = str(p.get("language") or "en").strip().lower()[:5]
    category = _norm_category(p.get("category"))
    labels = _safe_labels(p.get("rawLabels"))
    scene_hash = _scene_hash(p, category, labels)

    state = _state_get(scene_hash)
    remaining = _as_int(p.get("remainingDemons"), state["remainingDemons"])
    pending = state["pendingPickupCount"]
    fail_count = _as_int(state.get("pickupFailCount"), 0)

    if remaining <= 0:
        return _resp({
            "pickupConfirmed": False,
            "pickupStrength": 0,
            "pendingPickupCount": pending,
            "reason": "Scene already cleared.",
            "speechTextEnglish": "Scene is already clean.",
            "speechTextHindi": "यह सीन पहले ही साफ है।",
            "remainingDemons": 0,
            "sceneCleared": True,
            "sceneHash": scene_hash,
            "source": "BEDROCK",
        })

    # Soft evidence: labels can remain similar even after a valid pickup.
    # Do not hard-reject solely on high overlap.
    prev_labels = set(state.get("lastLabels") or [])
    curr_labels = set(labels)
    overlap_ratio = (len(prev_labels & curr_labels) / max(1, len(prev_labels))) if prev_labels else 0.0
    joined = " ".join(labels)
    has_hand_hint = ("hand" in joined) or ("person" in joined) or ("arm" in joined)

    prompt = f"""Return ONLY one JSON object with keys: pickupConfirmed(boolean), pickupStrength(integer 0..3), reason(string), speechTextEnglish(string), speechTextHindi(string).
sceneHash={scene_hash}
category={state.get('category') or category}
remainingDemons={remaining}
previousLabels={json.dumps(list(prev_labels)[:16], ensure_ascii=False)}
currentLabels={json.dumps(labels[:16], ensure_ascii=False)}
overlapRatio={overlap_ratio:.3f}
hasHandHint={has_hand_hint}
Rules:
- If clear pickup evidence is present, set pickupConfirmed=true.
- If uncertain but plausible pickup in active garbage scene, prefer pickupConfirmed=true with pickupStrength=1.
- Use false only when evidence strongly indicates no pickup.
"""

    model = _invoke_bedrock_json(prompt, max_tokens=180)
    confirmed = isinstance(model, dict) and _as_bool(model.get("pickupConfirmed"), False)
    strength = _as_int(model.get("pickupStrength"), 1 if confirmed else 0)

    if not confirmed:
        # Gameplay-safe fallback:
        # 1) if user already failed once, allow next attempt
        # 2) if scene changed enough or hand/person cue exists, allow single-item pickup
        heuristic_confirm = (
            (fail_count >= 1) or
            has_hand_hint or
            (overlap_ratio < 0.98 and len(curr_labels) > 0)
        )
        if heuristic_confirm and remaining > 0 and pending <= 0:
            strength = min(1, remaining)
            _state_put(
                scene_hash=scene_hash,
                remaining=remaining,
                pending=strength,
                pickup_fail_count=0,
                throw_fail_count=0,
                category=state.get("category") or category,
                labels=labels,
                ttl_sec=86400,
            )
            return _resp({
                "pickupConfirmed": True,
                "pickupStrength": strength,
                "pendingPickupCount": strength,
                "reason": "Pickup confirmed.",
                "speechTextEnglish": "Pickup confirmed. Find bin.",
                "speechTextHindi": "पिकअप कन्फर्म हुआ। डस्टबिन खोजें।",
                "remainingDemons": remaining,
                "sceneCleared": False,
                "sceneHash": scene_hash,
                "source": "BEDROCK",
            })

        fail_next = min(3, fail_count + 1)
        _state_put(
            scene_hash=scene_hash,
            remaining=remaining,
            pending=0,
            pickup_fail_count=fail_next,
            throw_fail_count=_as_int(state.get("throwFailCount"), 0),
            category=state.get("category") or category,
            labels=labels,
            ttl_sec=86400,
        )
        reason = "Pickup not confirmed. Show picked item to camera."
        if isinstance(model, dict) and str(model.get("reason") or "").strip():
            reason = str(model.get("reason")).strip()
        return _resp({
            "pickupConfirmed": False,
            "pickupStrength": 0,
            "pendingPickupCount": pending,
            "reason": reason,
            "speechTextEnglish": str(model.get("speechTextEnglish") if isinstance(model, dict) else "") or reason,
            "speechTextHindi": str(model.get("speechTextHindi") if isinstance(model, dict) else "") or "पिकअप कन्फर्म नहीं हुआ।",
            "remainingDemons": remaining,
            "sceneCleared": False,
            "sceneHash": scene_hash,
            "source": "BEDROCK",
        })

    strength = max(1, min(3, strength, remaining))
    pending_new = strength
    _state_put(
        scene_hash=scene_hash,
        remaining=remaining,
        pending=pending_new,
        pickup_fail_count=0,
        throw_fail_count=0,
        category=state.get("category") or category,
        labels=labels,
        ttl_sec=86400,
    )

    return _resp({
        "pickupConfirmed": True,
        "pickupStrength": strength,
        "pendingPickupCount": pending_new,
        "reason": str(model.get("reason") or "Pickup confirmed."),
        "speechTextEnglish": str(model.get("speechTextEnglish") or "Pickup confirmed. Find bin."),
        "speechTextHindi": str(model.get("speechTextHindi") or "पिकअप कन्फर्म हुआ। डस्टबिन खोजें।"),
        "remainingDemons": remaining,
        "sceneCleared": False,
        "sceneHash": scene_hash,
        "source": "BEDROCK",
    })


def _throw_handler(p):
    language = str(p.get("language") or "en").strip().lower()[:5]
    category = _norm_category(p.get("category"))
    labels = _safe_labels(p.get("rawLabels"))
    scene_hash = _scene_hash(p, category, labels)
    bin_scene_hash = str(p.get("binSceneHash") or "").strip()[:32]

    state = _state_get(scene_hash)
    remaining = state["remainingDemons"]
    pending = state["pendingPickupCount"]
    throw_fail_count = _as_int(state.get("throwFailCount"), 0)
    bin_detected = _as_bool(p.get("binDetected"), False)
    requested = _as_int(p.get("requestedDestroyCount"), 1)

    if remaining <= 0:
        return _resp({
            "throwConfirmed": False,
            "destroyCount": 0,
            "destroyedDemons": 0,
            "reason": "Scene already clean.",
            "speechTextEnglish": "Scene already clean.",
            "speechTextHindi": "सीन पहले ही साफ है।",
            "remainingDemons": 0,
            "sceneCleared": True,
            "sceneHash": scene_hash,
            "binSceneHash": bin_scene_hash,
            "source": "BEDROCK",
        })

    if pending <= 0:
        return _resp({
            "throwConfirmed": False,
            "destroyCount": 0,
            "destroyedDemons": 0,
            "reason": "No picked garbage pending. Pickup first.",
            "speechTextEnglish": "Pickup first, then throw.",
            "speechTextHindi": "पहले कचरा उठाएं, फिर फेंकें।",
            "remainingDemons": remaining,
            "sceneCleared": False,
            "sceneHash": scene_hash,
            "binSceneHash": bin_scene_hash,
            "source": "BEDROCK",
        })

    if not bin_detected:
        _state_put(
            scene_hash=scene_hash,
            remaining=remaining,
            pending=pending,
            pickup_fail_count=_as_int(state.get("pickupFailCount"), 0),
            throw_fail_count=min(3, throw_fail_count + 1),
            category=state.get("category") or category,
            labels=labels,
            last_bin_scene_hash=bin_scene_hash,
            ttl_sec=86400,
        )
        return _resp({
            "throwConfirmed": False,
            "destroyCount": 0,
            "destroyedDemons": 0,
            "reason": "Bin not confirmed. Point camera at bin.",
            "speechTextEnglish": "Bin not confirmed. Point camera at bin.",
            "speechTextHindi": "डस्टबिन कन्फर्म नहीं हुआ। कैमरा डस्टबिन पर रखें।",
            "remainingDemons": remaining,
            "sceneCleared": False,
            "sceneHash": scene_hash,
            "binSceneHash": bin_scene_hash,
            "source": "BEDROCK",
        })

    destroy_cap = max(1, min(3, requested, pending, remaining))

    prompt = f"""Return ONLY one JSON object with keys: throwConfirmed(boolean), destroyCount(integer 0..3), reason(string), speechTextEnglish(string), speechTextHindi(string).
sceneHash={scene_hash}
binSceneHash={bin_scene_hash}
remainingDemons={remaining}
pendingPickupCount={pending}
requestedDestroyCount={requested}
labels={json.dumps(labels[:16], ensure_ascii=False)}
Rules:
- If throw is clear, set throwConfirmed=true with destroyCount>=1.
- If uncertain but binDetected=true and pendingPickupCount>0, prefer throwConfirmed=true with destroyCount=1.
- Use false only when evidence strongly indicates no throw.
"""

    model = _invoke_bedrock_json(prompt, max_tokens=180)
    confirmed = isinstance(model, dict) and _as_bool(model.get("throwConfirmed"), False)

    joined = " ".join(labels)
    throw_context_tokens = {
        "bin", "dustbin", "dust", "trash", "garbage", "waste",
        "bucket", "basket", "container", "drop", "throw", "dispose",
        "dry", "wet", "recycle", "compost"
    }
    has_throw_context = any(tok in joined for tok in throw_context_tokens)
    heuristic_confirm = bin_detected and pending > 0 and (
        bool(bin_scene_hash) or has_throw_context or throw_fail_count >= 1
    )

    if not confirmed:
        if heuristic_confirm:
            destroy_count = max(1, min(destroy_cap, requested))
            remaining_new = max(0, remaining - destroy_count)
            pending_new = max(0, pending - destroy_count)

            _state_put(
                scene_hash=scene_hash,
                remaining=remaining_new,
                pending=pending_new,
                pickup_fail_count=0,
                throw_fail_count=0,
                category=state.get("category") or category,
                labels=labels,
                last_bin_scene_hash=bin_scene_hash,
                ttl_sec=86400,
            )

            cached_scene = _cache_get(scene_hash)
            if isinstance(cached_scene, dict) and cached_scene.get("isGarbage"):
                cached_scene["remainingDemons"] = remaining_new
                cached_scene["pendingPickupCount"] = pending_new
                cached_scene["recommendedDestroyCount"] = _recommended_destroy_count(remaining_new, pending_new)
                cached_scene["sceneHash"] = scene_hash
                _cache_put(scene_hash, cached_scene, ttl_sec=86400)

            return _resp({
                "throwConfirmed": True,
                "destroyCount": destroy_count,
                "destroyedDemons": destroy_count,
                "reason": "Throw confirmed.",
                "speechTextEnglish": "Throw confirmed.",
                "speechTextHindi": "थ्रो कन्फर्म हुआ।",
                "remainingDemons": remaining_new,
                "sceneCleared": remaining_new <= 0,
                "sceneHash": scene_hash,
                "binSceneHash": bin_scene_hash,
                "source": "BEDROCK",
            })

        _state_put(
            scene_hash=scene_hash,
            remaining=remaining,
            pending=pending,
            pickup_fail_count=0,
            throw_fail_count=min(3, throw_fail_count + 1),
            category=state.get("category") or category,
            labels=labels,
            last_bin_scene_hash=bin_scene_hash,
            ttl_sec=86400,
        )
        reason = "Throw not confirmed. Try again with bin in frame."
        if isinstance(model, dict) and str(model.get("reason") or "").strip():
            reason = str(model.get("reason")).strip()
        return _resp({
            "throwConfirmed": False,
            "destroyCount": 0,
            "destroyedDemons": 0,
            "reason": reason,
            "speechTextEnglish": str(model.get("speechTextEnglish") if isinstance(model, dict) else "") or reason,
            "speechTextHindi": str(model.get("speechTextHindi") if isinstance(model, dict) else "") or "थ्रो कन्फर्म नहीं हुआ।",
            "remainingDemons": remaining,
            "sceneCleared": False,
            "sceneHash": scene_hash,
            "binSceneHash": bin_scene_hash,
            "source": "BEDROCK",
        })

    destroy_count = _as_int(model.get("destroyCount") if isinstance(model, dict) else destroy_cap, destroy_cap)
    destroy_count = max(1, min(destroy_cap, destroy_count))

    remaining_new = max(0, remaining - destroy_count)
    pending_new = max(0, pending - destroy_count)

    _state_put(
        scene_hash=scene_hash,
        remaining=remaining_new,
        pending=pending_new,
        pickup_fail_count=0,
        throw_fail_count=0,
        category=state.get("category") or category,
        labels=labels,
        last_bin_scene_hash=bin_scene_hash,
        ttl_sec=86400,
    )

    # Keep cache state in sync for instant next scans.
    cached_scene = _cache_get(scene_hash)
    if isinstance(cached_scene, dict) and cached_scene.get("isGarbage"):
        cached_scene["remainingDemons"] = remaining_new
        cached_scene["pendingPickupCount"] = pending_new
        cached_scene["recommendedDestroyCount"] = _recommended_destroy_count(remaining_new, pending_new)
        cached_scene["sceneHash"] = scene_hash
        _cache_put(scene_hash, cached_scene, ttl_sec=86400)

    return _resp({
        "throwConfirmed": True,
        "destroyCount": destroy_count,
        "destroyedDemons": destroy_count,
        "reason": str(model.get("reason") if isinstance(model, dict) else "") or "Throw confirmed.",
        "speechTextEnglish": str(model.get("speechTextEnglish") if isinstance(model, dict) else "") or "Throw confirmed.",
        "speechTextHindi": str(model.get("speechTextHindi") if isinstance(model, dict) else "") or "थ्रो कन्फर्म हुआ।",
        "remainingDemons": remaining_new,
        "sceneCleared": remaining_new <= 0,
        "sceneHash": scene_hash,
        "binSceneHash": bin_scene_hash,
        "source": "BEDROCK",
    })


def lambda_handler(event, context):
    p = _payload(event)
    event_type = str(p.get("eventType") or "SCAN").strip().upper()

    try:
        if event_type == "SCAN":
            return _scan_handler(p)
        if event_type == "BIN_SCAN":
            return _bin_scan_handler(p)
        if event_type == "PICKUP_CHECK":
            return _pickup_handler(p)
        if event_type == "THROW_CHECK":
            return _throw_handler(p)

        return _resp({"error": f"Unsupported eventType: {event_type}"}, status=400)
    except Exception as e:
        print(f"UNHANDLED_ERR: {e}")
        return _resp({"error": "Internal error", "detail": str(e)}, status=500)
