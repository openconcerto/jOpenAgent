package org.jopenagent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.testsupport.Category;
import org.jopenagent.testsupport.Person;
import org.jopenagent.testsupport.TicketClassification;

class ObjectBinderTest {

    @Test
    void bindsRecordFromJsonObject() {
        JSONObject json = new JSONObject();
        json.put("category", "BILLING");
        json.put("rationale", "mentions an invoice");

        TicketClassification result = ObjectBinder.bind(json, TicketClassification.class);

        assertEquals(Category.BILLING, result.category());
        assertEquals("mentions an invoice", result.rationale());
    }

    @Test
    void bindsPojoFromJsonObject() {
        JSONObject json = new JSONObject();
        json.put("name", "Ada");
        json.put("age", 30);

        Person person = ObjectBinder.bind(json, Person.class);

        assertEquals("Ada", person.getName());
        assertEquals(30, person.getAge());
    }

    @Test
    void roundTripsRecordThroughJsonAndBack() {
        TicketClassification original = new TicketClassification(Category.TECHNICAL, "server is down");
        Object json = ObjectBinder.toJson(original);
        TicketClassification bound = ObjectBinder.bind(json, TicketClassification.class);
        assertEquals(original, bound);
    }

    @Test
    void bindsListOfStrings() {
        org.jopenkit.json.JSONArray array = new org.jopenkit.json.JSONArray();
        array.add("billing");
        array.add("refund");

        List<String> result = ObjectBinder.bind(array, new ParameterizedTypeCapture<List<String>>() {
        }.type());

        assertEquals(List.of("billing", "refund"), result);
    }

    @Test
    void bindsMapOfStringToInteger() {
        JSONObject json = new JSONObject();
        json.put("a", 1);
        json.put("b", 2);

        Map<String, Integer> result = ObjectBinder.bind(json, new ParameterizedTypeCapture<Map<String, Integer>>() {
        }.type());

        assertEquals(Integer.valueOf(1), result.get("a"));
        assertEquals(Integer.valueOf(2), result.get("b"));
    }

    @Test
    void bindsOptionalPresentAndEmpty() {
        Optional<String> present = ObjectBinder.bind("hello", new ParameterizedTypeCapture<Optional<String>>() {
        }.type());
        assertTrue(present.isPresent());
        assertEquals("hello", present.get());

        Optional<String> empty = ObjectBinder.bind(JSONObject.NULL, new ParameterizedTypeCapture<Optional<String>>() {
        }.type());
        assertFalse(empty.isPresent());
    }

    @Test
    void coercesStringNumberToInt() {
        Integer result = ObjectBinder.bind("42", int.class);
        assertEquals(42, result);
    }

    @Test
    void enumBindingIsCaseInsensitiveFallback() {
        Category result = ObjectBinder.bind("billing", Category.class);
        assertEquals(Category.BILLING, result);
    }

    @Test
    void invalidEnumValueThrowsBindingExceptionWithAllowedValues() {
        BindingException ex = assertThrows(BindingException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                ObjectBinder.bind("not-a-category", Category.class);
            }
        });
        assertTrue(ex.getMessage().contains("BILLING"));
    }

    private record ScoredItem(String label, int score) {
    }

    @Test
    void missingNullableRecordFieldBindsToNull() {
        JSONObject json = new JSONObject();
        json.put("category", "GENERAL");
        TicketClassification result = ObjectBinder.bind(json, TicketClassification.class);
        assertEquals(Category.GENERAL, result.category());
        assertEquals(null, result.rationale());
    }

    @Test
    void missingRequiredPrimitiveRecordFieldThrowsBindingException() {
        JSONObject json = new JSONObject();
        json.put("label", "urgent");
        // "score" (int) is missing -> primitive fields are required, unlike reference-typed fields.
        assertThrows(BindingException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                ObjectBinder.bind(json, ScoredItem.class);
            }
        });
    }

    @Test
    void bindResponseUnwrapsScalarUnderValueField() {
        int result = ObjectBinder.bindResponse("{\"value\": 5}", int.class);
        assertEquals(5, result);
    }

    @Test
    void bindResponseStripsMarkdownFences() {
        String raw = "```json\n{\"value\": 7}\n```";
        int result = ObjectBinder.bindResponse(raw, int.class);
        assertEquals(7, result);
    }

    @Test
    void bindResponseMissingValueFieldThrows() {
        assertThrows(BindingException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                ObjectBinder.bindResponse("{\"other\": 5}", int.class);
            }
        });
    }

    @Test
    void bindResponseInvalidJsonThrows() {
        assertThrows(BindingException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                ObjectBinder.bindResponse("not json at all", TicketClassification.class);
            }
        });
    }

    /** Captures a parameterized java.lang.reflect.Type via anonymous subclassing, without needing a JSON/reflection library. */
    private abstract static class ParameterizedTypeCapture<T> {
        java.lang.reflect.Type type() {
            java.lang.reflect.Type superclass = getClass().getGenericSuperclass();
            return ((java.lang.reflect.ParameterizedType) superclass).getActualTypeArguments()[0];
        }
    }
}
