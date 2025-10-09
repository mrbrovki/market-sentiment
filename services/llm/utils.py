import json
from config import logger, WOL_ENDPOINT
import os
import requests


def extract_scores_remote_LLM(api_json):
    results = []
    candidates = api_json.get("candidates", [])
    for candidate in candidates:
            content = candidate.get("content", {})
            parts = content.get("parts", [])

            for part in parts:
                text = part.get("text", "")
                
                firstIndex = text.find("[")
                lastIndex = text.rfind("]")
                try:
                    results = json.loads(text[firstIndex:lastIndex+1])
                except json.JSONDecodeError as e:
                    logger.error(f"Error decoding JSON: {e}")
                    return [] 
    return results

def extract_scores_local_LLM(api_json):
    results = []
    response = api_json.get("choices", [])[0].get("message", {}).get("content", "")
    word = "</think>"
    index = response.find(word)
    text = response[index + len(word):].lstrip() if index != -1 else response

    firstIndex = text.find("[")
    lastIndex = text.rfind("]")
    
    try:
        results = json.loads(text[firstIndex:lastIndex+1])
    except json.JSONDecodeError as e:
        logger.error(f"Error decoding JSON: {e}")
        return [] 
                
    return results

def load_prompts():
    prompts = {}
    prompts_dir = "prompts"

    for fname in os.listdir(prompts_dir):
        if fname.endswith(".txt"):
            asset_id = os.path.splitext(fname)[0]  # strip .txt
            with open(os.path.join(prompts_dir, fname), "r", encoding="utf-8") as tf:
                prompts[asset_id] = tf.read()
    return prompts

def wake_on_lan_request():
    try:
        response = requests.get(WOL_ENDPOINT, timeout=300)
        if response.status_code == 200:
            logger.info(f"WOL request successful.")
        else:
            logger.warning(f"WOL request returned status code {response.status_code}.")
    except requests.RequestException as e:
        logger.error(f"WOL request failed: {e}")