package com.marketshop.application.catalog;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-owned allow-list for catalog and editorial HTML. The public catalog asset path is
 * deliberately narrower than jsoup's normal URL protocols so persisted HTML never becomes
 * an external tracking surface or a container for temporary data/blob URLs.
 */
final class CatalogRichTextSanitizer {

    private static final Pattern CATALOG_IMAGE = Pattern.compile("/api/v1/catalog/assets/[1-9]\\d*");
    private static final Pattern IMAGE_WIDTH = Pattern.compile("(?:100|[1-9]\\d?)%");
    private static final Pattern SAFE_LINK_TARGET = Pattern.compile("_(?:blank|self|parent|top)");
    private static final Set<String> SAFE_LINK_PROTOCOLS = Set.of("http", "https", "mailto");
    private static final Safelist POLICY = new Safelist()
            .addTags("p", "br", "h2", "h3", "strong", "b", "em", "i", "u", "s", "strike",
                    "ol", "ul", "li", "blockquote", "a", "img")
            .addAttributes("a", "href", "title", "target", "rel")
            .addAttributes("img", "src", "alt", "width")
            .addProtocols("a", "href", SAFE_LINK_PROTOCOLS.toArray(String[]::new));
    private static final String SAFE_RELATIVE_LINK_MARKER = "https://market-shop.invalid";

    private CatalogRichTextSanitizer() {
    }

    static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return html == null ? null : "";
        }

        Document input = Jsoup.parseBodyFragment(html);
        for (Element image : input.select("img")) {
            String src = image.attr("src").trim();
            if (!CATALOG_IMAGE.matcher(src).matches()) {
                image.remove();
                continue;
            }
            String width = image.attr("width").trim();
            if (!validWidth(width)) {
                image.removeAttr("width");
            }
            image.removeAttr("height");
            image.removeAttr("style");
        }
        for (Element link : input.select("a")) {
            String href = withoutControlCharacters(link.attr("href")).trim();
            if (safeRelativeLink(href)) {
                link.attr("href", SAFE_RELATIVE_LINK_MARKER + href);
                link.attr("rel", "noopener noreferrer");
                continue;
            }
            if (safeAbsoluteLink(href)) {
                link.attr("href", href);
                link.attr("rel", "noopener noreferrer");
                continue;
            }
            link.unwrap();
        }
        for (Element link : input.select("a[target]")) {
            String target = link.attr("target").trim().toLowerCase();
            if (SAFE_LINK_TARGET.matcher(target).matches()) {
                link.attr("target", target);
            } else {
                link.removeAttr("target");
            }
        }

        Document clean = new org.jsoup.safety.Cleaner(POLICY).clean(input);
        clean.outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.xhtml);
        for (Element link : clean.select("a[href]")) {
            String href = link.attr("href");
            if (href.startsWith(SAFE_RELATIVE_LINK_MARKER)) {
                link.attr("href", href.substring(SAFE_RELATIVE_LINK_MARKER.length()));
            }
        }
        return clean.body().html();
    }

    private static boolean validWidth(String width) {
        if (!IMAGE_WIDTH.matcher(width).matches()) {
            return false;
        }
        int percentage = Integer.parseInt(width.substring(0, width.length() - 1));
        return percentage >= 10 && percentage <= 100;
    }

    private static boolean safeAbsoluteLink(String href) {
        int separator = href.indexOf(':');
        return separator > 0 && SAFE_LINK_PROTOCOLS.contains(href.substring(0, separator).toLowerCase());
    }

    private static boolean safeRelativeLink(String href) {
        return href.startsWith("/") && !href.startsWith("//") && !href.contains("\\");
    }

    private static String withoutControlCharacters(String value) {
        return value.replaceAll("[\\x00-\\x1F\\x7F]", "");
    }
}
