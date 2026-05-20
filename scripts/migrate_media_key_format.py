#!/usr/bin/env python3
"""Migrate mediaKey format across all contract fixtures.

Rules:
  - tmdb:movie:X  -> movie:tmdb:X
  - tmdb:series:X -> show:tmdb:X
  - tmdb:show:X   -> show:tmdb:X
  - tmdb:person:X -> person:tmdb:X
  - tmdb:episode:X -> episode:tmdb:{showId}:{s}:{e}
  - media_type/mediaType "series" -> "show" in expected/output contexts
"""

import json
import os
import re

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), '..', 'contracts', 'fixtures')


def transform_media_key_match(val):
    """Transform tmdb:{type}:{id} to {type}:tmdb:{id} with series->show."""
    m = re.match(r'^tmdb:(movie|series|show|person):(\d+)$', val)
    if m:
        t, i = m.group(1), m.group(2)
        if t in ('series', 'show'):
            return f'show:tmdb:{i}'
        return f'{t}:tmdb:{i}'
    m = re.match(r'^(/v1/metadata/titles/)tmdb:(movie|series|show|person):(\d+)$', val)
    if m:
        prefix, t, i = m.group(1), m.group(2), m.group(3)
        new_t = 'show' if t in ('series', 'show') else t
        return f'{prefix}{new_t}:tmdb:{i}'
    return None


def transform_id_with_type(val, media_type):
    """Transform tmdb:digits to type:tmdb:digits based on media_type."""
    type_prefix = 'show' if media_type in ('series', 'show') else media_type
    
    # Case-sensitive: TMDB -> TYPE:tmdb, tmdb -> type:tmdb
    m = re.match(r'^(TMDB|tmdb):(\d+)$', val)
    if m:
        prefix, i = m.group(1), m.group(2)
        new_p = type_prefix.upper() if prefix == 'TMDB' else type_prefix
        return f'{new_p}:tmdb:{i}'
    
    # Season IDs: TMDB|tmdb:X:season:Y -> TYPE:tmdb:X:season:Y
    m = re.match(r'^(TMDB|tmdb):(\d+):season:(\d+)$', val)
    if m:
        prefix, sid, snum = m.group(1), m.group(2), m.group(3)
        new_p = type_prefix.upper() if prefix == 'TMDB' else type_prefix
        return f'{new_p}:tmdb:{sid}:season:{snum}'
    
    return None


def transform_bridge_candidate(val):
    """Transform bridge candidate IDs: tmdb:X -> movie:tmdb:X, TMDB:X -> MOVIE:tmdb:X."""
    m = re.match(r'^(TMDB|tmdb):(\d+)(.*)$', val)
    if m:
        prefix, i, rest = m.group(1), m.group(2), m.group(3) or ''
        new_p = 'MOVIE' if prefix == 'TMDB' else 'movie'
        return f'{new_p}:tmdb:{i}{rest}'
    return None


def walk_and_transform(obj, fn):
    """Walk JSON tree, apply fn to string values, return changed copy."""
    if isinstance(obj, str):
        result = fn(obj)
        return result if result is not None else obj
    elif isinstance(obj, dict):
        return {k: walk_and_transform(v, fn) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [walk_and_transform(item, fn) for item in obj]
    return obj


def process_file(filepath):
    """Process a single fixture file."""
    with open(filepath, 'r') as f:
        content = f.read()
    
    relpath = os.path.relpath(filepath, FIXTURES_DIR)
    original = content
    
    # --- Simple text-level replacements (before JSON parsing) ---
    
    # 1. mediaKey, media_key, item_key general patterns
    # These are exact string replacements on the raw text
    for key in ['mediaKey', 'media_key', 'item_key']:
        for old_type in ['movie', 'series', 'show', 'person']:
            new_type = 'show' if old_type in ('series', 'show') else old_type
            content = content.replace(
                f'"{key}": "tmdb:{old_type}:',
                f'"{key}": "{new_type}:tmdb:'
            )
    
    # 2. Path values with embedded mediaKey
    for old_type in ['movie', 'series', 'show']:
        new_type = 'show' if old_type in ('series', 'show') else old_type
        content = content.replace(
            f'/v1/metadata/titles/tmdb:{old_type}:',
            f'/v1/metadata/titles/{new_type}:tmdb:'
        )
    
    # --- Episode mediaKey handling (specific known cases) ---
    
    episode_map = {
        'calendar_contract/v1/calendar_response_matches_server_contract.json':
            ('tmdb:episode:1001', 'episode:tmdb:500:1:3'),
        'calendar_contract/v1/this_week_response_requires_kind.json':
            ('tmdb:episode:2002', 'episode:tmdb:900:2:1'),
        'calendar_contract/v2/calendar_response_matches_server_contract.json':
            ('tmdb:episode:1001', 'episode:tmdb:500:1:3'),
        'media_state_contract/v4/continue_watching_item_requires_media_item.json':
            ('tmdb:episode:999', 'episode:tmdb:999:1:5'),
    }
    
    if relpath in episode_map:
        old_ep, new_ep = episode_map[relpath]
        content = content.replace(old_ep, new_ep)
        print(f"  Episode: {old_ep} -> {new_ep}")
    
    # --- Suite-specific JSON transformations ---
    
    data = json.loads(content)
    suite = data.get('suite', '')
    
    if suite in ('media_state_contract', 'watch_collections_contract', 'calendar_contract'):
        # Change media_type "series" -> "show" in expected normalized output
        # AND in input payloads (which represent server responses)
        expected = data.get('expected')
        if expected and isinstance(expected, dict):
            normalized = expected.get('normalized')
            if normalized and isinstance(normalized, dict):
                fix_normalized_media_type(normalized)
                items = normalized.get('items', [])
                if items:
                    for item in items:
                        if isinstance(item, dict):
                            fix_media_type_in_object(item)
        
        # Also transform mediaType/media_type "series" -> "show" in input payload tree
        payload = data.get('input', {}).get('payload') or data.get('input', {})
        if isinstance(payload, dict):
            fix_media_type_in_object(payload)
        
        content = json.dumps(data, indent=2, ensure_ascii=False) + '\n'
    
    # Write if changed
    if content != original:
        with open(filepath, 'w') as f:
            f.write(content)
        print(f"  ✓ Updated")
        return True
    else:
        print(f"  - No changes needed")
        return False





def fix_media_type_in_object(obj):
    """Recursively fix 'media_type': 'series' -> 'show' in an object tree."""
    if isinstance(obj, dict):
        if 'media_type' in obj and obj['media_type'] == 'series':
            obj['media_type'] = 'show'
        if 'mediaType' in obj and obj['mediaType'] == 'series':
            obj['mediaType'] = 'show'
        if 'media' in obj and isinstance(obj['media'], dict):
            fix_media_type_in_object(obj['media'])
        if 'mediaItem' in obj and isinstance(obj['mediaItem'], dict):
            fix_media_type_in_object(obj['mediaItem'])
        if 'relatedShow' in obj and isinstance(obj['relatedShow'], dict):
            fix_media_type_in_object(obj['relatedShow'])
        for v in obj.values():
            if isinstance(v, dict):
                fix_media_type_in_object(v)
            elif isinstance(v, list):
                for item in v:
                    if isinstance(item, dict):
                        fix_media_type_in_object(item)
    return obj


def fix_normalized_media_type(normalized):
    """Fix media_type in normalized output."""
    if normalized.get('media_type') == 'series':
        normalized['media_type'] = 'show'
    if normalized.get('items'):
        for item in normalized['items']:
            if isinstance(item, dict):
                fix_media_type_in_object(item)
    return normalized


def main():
    all_files = []
    for root, dirs, files in os.walk(FIXTURES_DIR):
        for f in sorted(files):
            if f.endswith('.json'):
                all_files.append(os.path.join(root, f))
    
    print(f"Found {len(all_files)} fixture files\n")
    
    updated = 0
    for fp in all_files:
        if process_file(fp):
            updated += 1
    
    print(f"\n{'='*50}")
    print(f"Updated {updated} / {len(all_files)} files")


if __name__ == '__main__':
    main()
