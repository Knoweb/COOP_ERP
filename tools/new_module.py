import os
import sys
import argparse
import shutil
import re

ALLOWED_SCHEMAS = {'catalogue', 'party', 'pricing', 'trading', 'inventory', 'pos', 'customers', 'reporting', 'integration'}

def copy_and_transform(src_path, dest_path, rules, is_file=False):
    if is_file:
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)
        with open(src_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        for search, replace in rules['content']:
            content = content.replace(search, replace)
        
        # Add TODO markers based on extension
        ext = os.path.splitext(dest_path)[1]
        if ext == '.java':
            content = "// TODO: Implement according to guide\n" + content
        elif ext == '.sql':
            content = "-- TODO: Define schema according to guide\n" + content
        elif ext in ['.ts', '.tsx']:
            content = "// TODO: Implement UI according to guide\n" + content
        elif ext in ['.yaml', '.yml']:
            content = "# TODO: Define API according to guide\n" + content
            
        with open(dest_path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Created: {dest_path}")
    else:
        for root, dirs, files in os.walk(src_path):
            dirs[:] = [d for d in dirs if d not in ['build', '.DS_Store'] and not d.endswith('.class')]
            
            for file in files:
                if file.endswith('.class') or file == '.DS_Store':
                    continue
                
                src_file = os.path.join(root, file)
                rel_path = os.path.relpath(src_file, src_path)
                
                # Transform filename
                dest_file_rel = rel_path
                for search, replace in rules['filename']:
                    dest_file_rel = dest_file_rel.replace(search, replace)
                
                dest_file = os.path.join(dest_path, dest_file_rel)
                copy_and_transform(src_file, dest_file, rules, is_file=True)

def main():
    parser = argparse.ArgumentParser(description="Scaffold a new module from the hello template.")
    parser.add_argument("--name", required=True, help="Name of the new module (e.g., m2catalogue)")
    parser.add_argument("--schema", required=True, help="Database schema name (e.g., catalogue)")
    args = parser.parse_args()

    name = args.name
    schema = args.schema

    if not re.match(r'^[a-z0-9]+$', name):
        print(f"Error: Invalid module name '{name}'. Must be valid lowercase alphanumeric.")
        sys.exit(1)
        
    if schema not in ALLOWED_SCHEMAS:
        print(f"Error: Invalid schema '{schema}'. Allowed schemas: {', '.join(ALLOWED_SCHEMAS)}")
        sys.exit(1)

    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
    
    # Paths
    backend_src = os.path.join(project_root, 'backend', 'app', 'src', 'main', 'java', 'lk', 'coopfed', 'knoweb')
    hello_backend = os.path.join(backend_src, 'hello')
    target_backend = os.path.join(backend_src, name)
    
    hello_migration = os.path.join(project_root, 'backend', 'app', 'src', 'main', 'resources', 'db', 'migration', 'hello')
    target_migration = os.path.join(project_root, 'backend', 'app', 'src', 'main', 'resources', 'db', 'migration', name)
    
    hello_openapi = os.path.join(project_root, 'backend', 'app', 'src', 'main', 'resources', 'openapi', 'hello.yaml')
    target_openapi = os.path.join(project_root, 'backend', 'app', 'src', 'main', 'resources', 'openapi', f"{name}.yaml")
    
    hello_web = os.path.join(project_root, 'web', 'src', 'modules', 'hello')
    target_web = os.path.join(project_root, 'web', 'src', 'modules', name)

    # Safety checks
    for p in [target_backend, target_migration, target_openapi, target_web]:
        if os.path.exists(p):
            print(f"Error: Target path already exists: {p}")
            sys.exit(1)

    # Transformation rules
    name_cap = name.capitalize()
    schema_cap = schema.capitalize()
    
    rules = {
        'filename': [
            ('Hello', name_cap),
            ('Greeting', schema_cap),
            ('hello', name),
            ('greeting', schema)
        ],
        'content': [
            ('lk.coopfed.knoweb.hello', f'lk.coopfed.knoweb.{name}'),
            ('HelloController', f'{name_cap}Controller'),
            ('RegisterGreeting', f'Register{schema_cap}'),
            ('GreetingRegistered', f'{schema_cap}Registered'),
            ('GreetingQueries', f'{schema_cap}Queries'),
            ('Greeting', schema_cap),
            ('greeting', schema),
            ('hello_world', f'{name}_placeholder'),
            ('hello', name)
        ]
    }

    print(f"Scaffolding module: {name} (schema: {schema})")
    
    # 1. Backend Java
    if os.path.exists(hello_backend):
        copy_and_transform(hello_backend, target_backend, rules)
        
    # 2. Database Migration
    if os.path.exists(hello_migration):
        # We want to specifically rename V0001__greeting.sql to V0001__initial.sql
        os.makedirs(target_migration, exist_ok=True)
        src_sql = os.path.join(hello_migration, 'V0001__greeting.sql')
        if os.path.exists(src_sql):
            dest_sql = os.path.join(target_migration, 'V0001__initial.sql')
            
            sql_rules = {
                'filename': rules['filename'],
                'content': [
                    ('hello', schema),
                    ('greeting', schema)
                ]
            }
            
            copy_and_transform(src_sql, dest_sql, sql_rules, is_file=True)

    # 3. OpenAPI Spec
    if os.path.exists(hello_openapi):
        copy_and_transform(hello_openapi, target_openapi, rules, is_file=True)

    # 4. React Web
    if os.path.exists(hello_web):
        copy_and_transform(hello_web, target_web, rules)

    print("Module scaffolding complete.")

if __name__ == '__main__':
    main()
