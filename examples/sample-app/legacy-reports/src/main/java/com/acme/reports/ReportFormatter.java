package com.acme.reports;

import com.acme.common.TextUtils;
import java.util.List;
import java.util.Map;

public class ReportFormatter {
    public String header(String title) { return "== " + TextUtils.truncate(title, 40) + " =="; }
    public String row(Map<String, String> values) { return TextUtils.fill("${name}: ${value}", values); }
    public String render(String title, List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder(header(title)).append('\n');
        for (Map<String, String> r : rows) sb.append(row(r)).append('\n');
        return sb.toString();
    }
}
