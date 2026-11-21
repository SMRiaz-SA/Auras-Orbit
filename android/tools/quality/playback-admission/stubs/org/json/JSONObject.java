package org.json;
public class JSONObject {
 public JSONObject(){} public JSONObject(String text)throws JSONException{} public JSONObject put(String key,Object value)throws JSONException{return this;}
 public int optInt(String key,int fallback){return fallback;} public long optLong(String key,long fallback){return fallback;}
 public String optString(String key,String fallback){return fallback;} public String optString(String key){return "";}
 public JSONObject optJSONObject(String key){return null;} public JSONArray optJSONArray(String key){return null;}
 public JSONObject getJSONObject(String key)throws JSONException{return new JSONObject();}
 public String getString(String key)throws JSONException{return "";} public long getLong(String key)throws JSONException{return 0;}
 public int getInt(String key)throws JSONException{return 0;} public int length(){return 0;} public JSONArray names(){return null;}
 public String toString(){return "{}";}
}
