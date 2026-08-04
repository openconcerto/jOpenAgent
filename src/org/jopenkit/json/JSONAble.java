package org.jopenkit.json;

public interface JSONAble {

    public JSONObject toJSON();

    public void fromJSON(JSONObject json);
}
