package com.cms.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders article HTML into chunks Telegram accepts with parse_mode=HTML.
 *
 * <p>Telegram supports a small inline subset only - b, i, u, s, a, code, pre, blockquote -
 * and knows nothing about the h2/p/ul/li the articles are written in, so block structure is
 * flattened into text separated by blank lines. One message also caps at 4096 characters,
 * which a 700-1000 word article always exceeds.
 */
public final class TelegramHtmlFormatter {

    /** Telegram's hard cap on the text of a single sendMessage call. */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    private static final String BLOCK_SEPARATOR = "\n\n";

    private static final Set<String> BLOCK_TAGS = Set.of(
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "div", "blockquote", "section", "article");

    private TelegramHtmlFormatter() {
    }

    /** Article HTML to ready-to-send Telegram-HTML chunks, in order. */
    public static List<String> toMessages(String articleHtml) {
        return split(render(articleHtml));
    }

    /** Escapes the three characters Telegram reads as markup. Ampersand must go first. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String render(String articleHtml) {
        if (articleHtml == null || articleHtml.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(articleHtml);
        StringBuilder out = new StringBuilder();
        for (Node node : document.body().childNodes()) {
            appendBlock(node, out);
        }
        return out.toString().strip();
    }

    private static void appendBlock(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            if (!text.text().isBlank()) {
                out.append(escape(text.text().strip())).append(BLOCK_SEPARATOR);
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        switch (element.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" ->
                    out.append("<b>").append(inline(element)).append("</b>").append(BLOCK_SEPARATOR);
            case "ul", "ol" -> {
                for (Element item : element.select("> li")) {
                    out.append("• ").append(inline(item)).append('\n');
                }
                out.append('\n');
            }
            case "br" -> out.append('\n');
            default -> {
                if (hasBlockChildren(element)) {
                    for (Node child : element.childNodes()) {
                        appendBlock(child, out);
                    }
                    return;
                }
                String rendered = inline(element);
                if (!rendered.isBlank()) {
                    out.append(rendered).append(BLOCK_SEPARATOR);
                }
            }
        }
    }

    private static boolean hasBlockChildren(Element element) {
        return element.children().stream().anyMatch(child -> BLOCK_TAGS.contains(child.tagName()));
    }

    private static String inline(Element element) {
        StringBuilder out = new StringBuilder();
        for (Node node : element.childNodes()) {
            appendInline(node, out);
        }
        return out.toString().strip();
    }

    private static void appendInline(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(escape(text.text()));
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        switch (element.tagName()) {
            case "b", "strong" -> out.append("<b>").append(inline(element)).append("</b>");
            case "i", "em" -> out.append("<i>").append(inline(element)).append("</i>");
            case "u", "ins" -> out.append("<u>").append(inline(element)).append("</u>");
            case "s", "strike", "del" -> out.append("<s>").append(inline(element)).append("</s>");
            case "code" -> out.append("<code>").append(inline(element)).append("</code>");
            case "br" -> out.append('\n');
            case "a" -> {
                String href = element.attr("href");
                if (href.isBlank()) {
                    out.append(inline(element));
                } else {
                    out.append("<a href=\"").append(escape(href)).append("\">")
                            .append(inline(element)).append("</a>");
                }
            }
            // Anything Telegram does not know is dropped; its text survives.
            default -> out.append(inline(element));
        }
    }

    static List<String> split(String rendered) {
        List<String> chunks = new ArrayList<>();
        if (rendered == null || rendered.isEmpty()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String block : rendered.split("\n{2,}")) {
            if (block.length() > MAX_MESSAGE_LENGTH) {
                flush(current, chunks);
                hardSplit(block, chunks);
                continue;
            }
            int projected = current.isEmpty()
                    ? block.length()
                    : current.length() + BLOCK_SEPARATOR.length() + block.length();
            if (projected > MAX_MESSAGE_LENGTH) {
                flush(current, chunks);
            }
            if (!current.isEmpty()) {
                current.append(BLOCK_SEPARATOR);
            }
            current.append(block);
        }
        flush(current, chunks);
        return chunks;
    }

    private static void flush(StringBuilder current, List<String> chunks) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    /**
     * Last resort for one block longer than the limit. Markup is stripped first: cutting
     * inside a tag, or between an opening and closing pair, yields HTML that Telegram
     * rejects outright, and a paragraph this long is malformed content anyway.
     */
    private static void hardSplit(String block, List<String> chunks) {
        String plain = escape(Jsoup.parse(block).text());
        int start = 0;
        while (start < plain.length()) {
            int end = Math.min(start + MAX_MESSAGE_LENGTH, plain.length());
            if (end < plain.length()) {
                int lastSpace = plain.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            chunks.add(plain.substring(start, end).strip());
            start = end;
        }
    }
}
