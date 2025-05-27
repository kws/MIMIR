package com.kajsiebert.sip.openai.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ConsumerArray<T> {
    private final List<Consumer<T>> consumers = new ArrayList<>();

    public void add(Consumer<T> consumer) {
        consumers.add(consumer);
    }

    public void accept(T t) {
        consumers.forEach(consumer -> consumer.accept(t));
    }

}
