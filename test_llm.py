
import os
import sys
import json
import urllib.request
import urllib.error

def load_api_key():
    try:
        with open('local.properties', 'r') as f:
            for line in f:
                if line.strip().startswith('groq.api.key='):
                    return line.strip().split('=', 1)[1]
    except FileNotFoundError:
        print("Error: local.properties not found.")
        return None
    return None

def build_prompt(command):
    # Mimic the simplified prompt structure from the app
    system_prompt = """
    You are a command rewriter for a Z-machine text adventure game.
    Your job is to translate the player's natural language intent into a valid 1-2 word parser command.
    
    Vocabulary:
    - Verbs: take, drop, look, inventory, go, north, south, east, west, up, down
    - Nouns sword, lantern, key
    
    Rules:
    1. Output ONLY the rewritten command.
    2. Do not explain.
    3. If you cannot rewrite it, output <NO_VALID_REWRITE>.
    """
    
    user_prompt = f"Command: {command}\nError: Command not understood"
    
    return {
        "model": "llama-3.1-8b-instant",
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 50
    }

def call_groq(api_key, command):
    url = "https://api.groq.com/openai/v1/chat/completions"
    data = build_prompt(command)
    json_data = json.dumps(data).encode('utf-8')
    
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
        "User-Agent": "IFAutoFab-Test/1.0"
    }
    
    import ssl
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    try:
        req = urllib.request.Request(url, data=json_data, headers=headers, method='POST')
        with urllib.request.urlopen(req, context=ctx) as response:
            result = json.loads(response.read().decode('utf-8'))
            return result['choices'][0]['message']['content']
    except urllib.error.HTTPError as e:
        print(f"HTTP Error: {e.code} - {e.reason}")
        print(e.read().decode('utf-8'))
        return None
    except Exception as e:
        print(f"Error: {e}")
        return None

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 test_llm.py \"your command here\"")
        print("Example: python3 test_llm.py \"grab the sword\"")
        sys.exit(1)

    command = sys.argv[1]
    print(f"Checking configuration and testing LLM rewrite for: '{command}'")
    
    api_key = load_api_key()
    if not api_key:
        print("❌ Could not find 'groq.api.key' in local.properties")
        print("Please add 'groq.api.key=your_key' to '../IFAutoFab-llm/local.properties'")
        sys.exit(1)
        
    print(f"✅ Found API Key: {api_key[:4]}...{api_key[-4:]}")
    
    print("Sending request to Groq (llama-3.1-8b-instant)...")
    result = call_groq(api_key, command)
    
    if result:
        print(f"\nOriginal: '{command}'")
        print(f"Rewritten: '{result}'")
        print("\n✅ Test Passed! The API integration is working.")
    else:
        print("\n❌ Test Failed.")

if __name__ == "__main__":
    main()
