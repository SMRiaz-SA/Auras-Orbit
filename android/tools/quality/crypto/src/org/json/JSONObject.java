package org.json;
public class JSONObject {private final String body; public JSONObject(String body){if(body==null||!body.trim().startsWith("{")||!body.trim().endsWith("}"))throw new JSONException("malformed stub JSON");this.body=body;} public String toString(){return body;} }
