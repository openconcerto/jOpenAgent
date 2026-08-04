package org.jopenkit.json;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Format;

public final class TypeAdapters {
    private TypeAdapters() {
    }

    static public final class IdentityTypeAdapter<T> extends WithClassTypeAdapter<T> {

        public IdentityTypeAdapter(Class<T> clazz) {
            super(clazz);
        }

        @Override
        public T fromJSON(Object o) {
            return o == JSONObject.NULL ? null : this.getParsedClass().cast(o);
        }

        @Override
        public Object toJSON(T o) {
            return o == null ? JSONObject.NULL : o;
        }
    };

    static private final TypeAdapter<Boolean> BOOLEAN_ADAPTER = new IdentityTypeAdapter<>(Boolean.class);
    static private final TypeAdapter<String> STRING_ADAPTER = new IdentityTypeAdapter<>(String.class);

    public final static TypeAdapter<Boolean> getBooleanAdapter() {
        return BOOLEAN_ADAPTER;
    }

    public final static TypeAdapter<String> getStringAdapter() {
        return STRING_ADAPTER;
    }

    static private final TypeAdapter<Integer> INT_ADAPTER = new TypeAdapter<Integer>() {

        @Override
        public Object toJSON(Integer o) {
            return o;
        }

        @Override
        public Integer fromJSON(Object o) {
            if (o instanceof Integer)
                return (Integer) o;
            final int intVal;
            if (o instanceof BigDecimal) {
                intVal = ((BigDecimal) o).intValueExact();
            } else if (o instanceof BigInteger) {
                intVal = ((BigInteger) o).intValueExact();
            } else {
                intVal = Math.toIntExact((Long) o);
            }
            return intVal;
        }
    };

    public final static TypeAdapter<Integer> getIntAdapter() {
        return INT_ADAPTER;
    }

    static private final TypeAdapter<Color> COLOR_ADAPTER = new TypeAdapter<Color>() {
        static private final char SHARP = '#';

        @Override
        public Color fromJSON(Object o) {
            if (o.toString().charAt(0) != SHARP)
                throw new IllegalArgumentException("Not a Color : " + o);
            return Color.decode(o.toString());
        }

        @Override
        public Object toJSON(Color o) {
            String hexString = Integer.toHexString(o.getRGB());
            if (hexString.length() > 6) {
                hexString = hexString.substring(2, hexString.length());
            }
            return SHARP + hexString;
        }
    };

    public final static TypeAdapter<Color> getColorAdapter() {
        return COLOR_ADAPTER;
    }

    public static <E extends Enum<E>> TypeAdapter<E> forEnum(final Class<E> enumClass) {
        return new TypeAdapter<E>() {
            @Override
            public E fromJSON(Object o) {
                return Enum.valueOf(enumClass, o.toString());
            }

            @Override
            public Object toJSON(E o) {
                return ((Enum<?>) o).name();
            }
        };
    }

    public static <E extends Enum<E>> TypeAdapter<E> forEnumLowerCase(final Class<E> enumClass) {
        return new TypeAdapter<E>() {
            @Override
            public E fromJSON(Object o) {
                return Enum.valueOf(enumClass, o.toString().toUpperCase());
            }

            @Override
            public Object toJSON(E o) {
                return ((Enum<?>) o).name().toLowerCase();
            }
        };
    }

    public static <E> TypeAdapter<E> fromFormat(final Class<E> clazz, final Format fmt) {
        return new TypeAdapter<E>() {
            @Override
            public E fromJSON(Object o) {
                final String sparam = (String) o;
                final Object c;
                try {
                    c = fmt.parseObject(sparam);
                } catch (java.text.ParseException e) {
                    throw new IllegalArgumentException("The format of  <" + o + "> is not valid", e);
                }
                return clazz.cast(c);
            }

            @Override
            public Object toJSON(E o) {
                return fmt.format(o);
            }
        };
    }
}
