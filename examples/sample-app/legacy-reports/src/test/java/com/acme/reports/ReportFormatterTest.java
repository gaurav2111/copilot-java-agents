package com.acme.reports;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ReportFormatterTest {
    private ReportFormatter formatter;

    @Before
    public void setUp() { formatter = new ReportFormatter(); }

    @Test
    public void headerIsWrappedInMarkers() {
        assertThat(formatter.header("Sales"), equalTo("== Sales =="));
    }

    @Test
    public void renderJoinsRows() {
        String out = formatter.render("T", List.of(Map.of("name", "a", "value", "1")));
        assertThat(out, startsWith("== T =="));
        assertThat(out, containsString("a: 1"));
    }
}
