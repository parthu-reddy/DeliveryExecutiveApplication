import glob
import re

files = [
    "Customer App Architecture And Integrations.md",
    "Food Delivery App Use Cases.md",
    "Restaurant App Architecture Research.md",
    "Restaurant App Production Architecture.md",
    "The Delivery Executive and Restaurant Partner Applications.md"
]

for file in files:
    try:
        with open(file, 'r') as f:
            content = f.read()
            print(f"=== {file} ===")
            
            # Find all Use Case Names
            use_cases = re.findall(r'Use Case Name.*?\|\s*(.*?)\s*\|', content)
            for uc in use_cases:
                print(f" - Use Case: {uc}")
                
            # Find missing functionality keywords
            print(" - Action Items / Must Implement:")
            musts = re.findall(r'([^.]*?must[^.]*\.)', content, re.IGNORECASE)
            for m in musts:
                if 'implement' in m.lower() or 'require' in m.lower():
                    print(f"   * {m.strip()}")
            print("\n")
    except Exception as e:
        print(f"Error reading {file}: {e}")
