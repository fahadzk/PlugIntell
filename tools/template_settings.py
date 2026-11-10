"""Validated declarative settings subset for external template manifests."""
import math
import re

def finite_number(value):
    if type(value) not in (int,float):return False
    try:return math.isfinite(value)
    except OverflowError:return False

def check_value(name,value,rules,enumeration=True):
    kind=rules.get("type")
    valid=(kind=="string" and isinstance(value,str) or kind=="boolean" and type(value) is bool or
        kind=="number" and finite_number(value) or
        kind=="integer" and finite_number(value) and int(value)==value)
    if not valid:raise ValueError("Invalid template setting type: "+name)
    if isinstance(value,str):
        if len(value)>rules.get("maxLength",2048):raise ValueError("Template setting is too long: "+name)
        if rules.get("format")=="color" and not re.fullmatch(r"#[0-9A-Fa-f]{6}",value):raise ValueError("Use a six-digit hex color: "+name)
    if type(value) in (int,float) and (value<rules.get("minimum",-math.inf) or value>rules.get("maximum",math.inf)):raise ValueError("Template setting is outside its allowed range: "+name)
    if enumeration and "enum" in rules and value not in rules["enum"]:raise ValueError("Template setting is outside its allowed choices: "+name)

def validate_schema(schema):
    if schema is None:return
    if not isinstance(schema,dict) or schema.get("type")!="object" or set(schema)-{"type","properties","additionalProperties","required"}:raise ValueError("Invalid template settingsSchema")
    if "additionalProperties" in schema and type(schema["additionalProperties"]) is not bool:raise ValueError("additionalProperties must be boolean")
    properties=schema.get("properties",{})
    if not isinstance(properties,dict) or len(properties)>32:raise ValueError("Invalid template settings properties")
    for name,rules in properties.items():
        if not isinstance(name,str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,47}",name) or not isinstance(rules,dict):raise ValueError("Invalid template setting definition")
        if set(rules)-{"type","title","description","default","enum","maxLength","minimum","maximum","format"} or rules.get("type") not in ("string","boolean","integer","number"):raise ValueError("Unsupported template setting rule")
        for label in ("title","description"):
            if label in rules and (not isinstance(rules[label],str) or len(rules[label])>500):raise ValueError("Invalid template setting label")
        if "format" in rules and (rules["type"]!="string" or rules["format"]!="color"):raise ValueError("Unsupported template setting format")
        if "maxLength" in rules and (type(rules["maxLength"]) not in (int,float) or not 0<=rules["maxLength"]<=2048 or int(rules["maxLength"])!=rules["maxLength"]):raise ValueError("Invalid maxLength")
        for bound in ("minimum","maximum"):
            if bound in rules and not finite_number(rules[bound]):raise ValueError("Invalid numeric bound")
        if "enum" in rules:
            if not isinstance(rules["enum"],list) or not 1<=len(rules["enum"])<=32:raise ValueError("Invalid enum")
            for choice in rules["enum"]:check_value(name,choice,rules,False)
        if "default" in rules:check_value(name,rules["default"],rules)
    required=schema.get("required",[])
    if not isinstance(required,list) or len(required)>32 or any(not isinstance(key,str) or key not in properties for key in required):raise ValueError("Invalid required settings")

def validate(settings,schema=None):
    if not isinstance(settings,dict) or len(settings)>32:raise ValueError("Template settings must be an object with at most 32 fields")
    for key,value in settings.items():
        if not (isinstance(value,str) and len(value)<=2048 or type(value) is bool or finite_number(value)):raise ValueError("Template settings must be bounded simple values")
    validate_schema(schema)
    if schema is None:return
    properties=schema.get("properties",{})
    for key,value in settings.items():
        if key in properties:check_value(key,value,properties[key])
        elif schema.get("additionalProperties") is False:raise ValueError("Unknown template setting: "+key)
    if any(key not in settings for key in schema.get("required",[])):raise ValueError("Missing required template setting")

def defaults(schema):
    validate_schema(schema)
    return {key:rules["default"] for key,rules in (schema or {}).get("properties",{}).items() if "default" in rules}
