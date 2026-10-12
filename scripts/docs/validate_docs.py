#!/usr/bin/env python3
import os
import re
import sys

def validate_markdown_table(line):
    """Check for malformed pipe table rows like || cell | cell |"""
    # Pattern for malformed table rows: starts with || but doesn't have proper ending
    stripped = line.strip()
    if stripped.startswith('||') and not stripped.endswith('|'):
        return f"Malformed table row detected: '{stripped}' - should end with |"
    return None

def validate_local_link(link, base_path):
    """Validate a local markdown link"""
    # Remove anchor/fragment if present
    if '#' in link:
        link_path, _ = link.split('#', 1)
    else:
        link_path = link

    # Check if it's a relative path
    if link_path.startswith('http://') or link_path.startswith('https://'):
        return None  # External link - skip validation

    # Construct full path
    full_path = os.path.normpath(os.path.join(base_path, link_path))

    # Check if file exists
    if not os.path.exists(full_path):
        return f"Broken link: '{link}' -> file not found: '{full_path}'"

    return None

def validate_markdown_file(file_path):
    """Validate a single markdown file"""
    errors = []
    base_path = os.path.dirname(os.path.abspath(file_path))

    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
    except Exception as e:
        errors.append(f"Could not read file {file_path}: {e}")
        return errors

    for i, line in enumerate(lines, 1):
        line = line.rstrip('\n\r')

        # Check for malformed table rows
        table_error = validate_markdown_table(line)
        if table_error:
            errors.append(f"{file_path}:{i}: {table_error}")

        # Find markdown links: [text](link)
        # Simple regex for markdown links
        link_matches = re.findall(r'\[([^\]]*)\]\(([^)]+)\)', line)
        for match in link_matches:
            link_text, link_url = match
            link_error = validate_local_link(link_url, base_path)
            if link_error:
                errors.append(f"{file_path}:{i}: {link_error} in link '[{link_text}]({link_url})'")

    return errors

def main():
    # If file arguments provided, validate only those files
    if len(sys.argv) > 1:
        files_to_check = sys.argv[1:]
    else:
        # Default documentation files to check (from README Core documentation table)
        docs_to_check = [
            "README.md",
            "docs/guides/PROJECT-GUIDE.md",
            "docs/guides/API-REFERENCE.md",
            "docs/guides/IMPLEMENTATION-STATUS.md",
            "docs/guides/HTTP-TRANSPORT-EXAMPLE.md",
            "docs/guides/TRANSPORT-SSE.md",
            "docs/guides/USER_GUIDE.md",
            "docs/architecture/MCP-COMPATIBILITY-2026.md",
            "docs/architecture/MCP-PORTING-PLAN.md"
        ]

        # Files that should exist but might be misplaced
        potential_misplaced = [
            "docs/guides/MCP-COMPATIBILITY-2026.md",
            "docs/guides/MCP-PORTING-PLAN.md"
        ]

        files_to_check = docs_to_check
        # We'll handle misplaced files separately below

    all_errors = []

    # Check specified files
    for file_path in files_to_check:
        if os.path.exists(file_path):
            errors = validate_markdown_file(file_path)
            all_errors.extend(errors)
        else:
            all_errors.append(f"MISSING FILE: {file_path}")

    # Check for misplaced files (only when using default file list)
    if len(sys.argv) <= 1:
        potential_misplaced = [
            "docs/guides/MCP-COMPATIBILITY-2026.md",
            "docs/guides/MCP-PORTING-PLAN.md"
        ]
        for misplaced in potential_misplaced:
            if os.path.exists(misplaced):
                all_errors.append(f"MISPLACED FILE: {misplaced} exists but should be in docs/architecture/ based on README links")

    # Print results
    if all_errors:
        print("❌ Markdown validation failed:")
        for error in all_errors:
            print(f"  {error}")
        return 1
    else:
        print("✅ All markdown validation checks passed!")
        return 0

if __name__ == "__main__":
    sys.exit(main())