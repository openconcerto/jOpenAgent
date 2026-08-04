package org.jopenkit.json;

public abstract class WithClassTypeAdapter<T> implements TypeAdapter<T> {
    private final Class<T> clazz;

    public WithClassTypeAdapter(Class<T> clazz) {
        super();
        this.clazz = clazz;
    }

    protected final Class<T> getParsedClass() {
        return this.clazz;
    }
}
