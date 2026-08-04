package org.jopenkit.json;

public interface TypeAdapter<T> {

    Object toJSON(final T o);

    T fromJSON(final Object o);
}
