package com.cms.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramHtmlFormatterTest {

    @Test
    void rendersHeadingsAsBold() {
        assertThat(TelegramHtmlFormatter.render("<h2>Заголовок</h2><p>Текст</p>"))
                .isEqualTo("<b>Заголовок</b>\n\nТекст");
    }

    @Test
    void rendersListItemsWithBullets() {
        assertThat(TelegramHtmlFormatter.render("<ul><li>Раз</li><li>Два</li></ul>"))
                .isEqualTo("• Раз\n• Два");
    }

    @Test
    void keepsInlineEmphasis() {
        assertThat(TelegramHtmlFormatter.render("<p>Это <strong>важно</strong> и <em>ясно</em></p>"))
                .isEqualTo("Это <b>важно</b> и <i>ясно</i>");
    }

    @Test
    void dropsTagsTelegramDoesNotKnowButKeepsTheirText() {
        assertThat(TelegramHtmlFormatter.render("<p>Текст <span>внутри</span></p>"))
                .isEqualTo("Текст внутри");
    }

    @Test
    void escapesMarkupCharactersInText() {
        // Without this Telegram rejects the whole message as malformed HTML.
        assertThat(TelegramHtmlFormatter.render("<p>5 &lt; 10 &amp; 20 &gt; 3</p>"))
                .isEqualTo("5 &lt; 10 &amp; 20 &gt; 3");
    }

    @Test
    void keepsLinksWithHref() {
        assertThat(TelegramHtmlFormatter.render("<p>См. <a href=\"https://a.ru\">тут</a></p>"))
                .isEqualTo("См. <a href=\"https://a.ru\">тут</a>");
    }

    @Test
    void flattensNestedBlockContainers() {
        assertThat(TelegramHtmlFormatter.render("<div><p>Раз</p><p>Два</p></div>"))
                .isEqualTo("Раз\n\nДва");
    }

    @Test
    void shortArticleStaysOneMessage() {
        assertThat(TelegramHtmlFormatter.toMessages("<h2>Т</h2><p>Коротко</p>")).hasSize(1);
    }

    @Test
    void longArticleIsSplitOnParagraphBoundaries() {
        String paragraph = "<p>" + "Предложение про здоровье. ".repeat(4) + "</p>";
        List<String> messages = TelegramHtmlFormatter.toMessages(paragraph.repeat(80));

        assertThat(messages).hasSizeGreaterThan(1);
        assertThat(messages).allSatisfy(m ->
                assertThat(m.length()).isLessThanOrEqualTo(TelegramHtmlFormatter.MAX_MESSAGE_LENGTH));
        assertThat(messages).allSatisfy(m -> assertThat(m).doesNotStartWith(" "));
    }

    @Test
    void singleOverlongParagraphIsCutWithoutBreakingMarkup() {
        String giant = "<p>" + "слово ".repeat(1200) + "</p>";

        List<String> messages = TelegramHtmlFormatter.toMessages(giant);

        assertThat(messages).hasSizeGreaterThan(1);
        assertThat(messages).allSatisfy(m ->
                assertThat(m.length()).isLessThanOrEqualTo(TelegramHtmlFormatter.MAX_MESSAGE_LENGTH));
        // A chunk must never end inside a tag.
        assertThat(messages).allSatisfy(m -> assertThat(m.lastIndexOf('<')).isLessThanOrEqualTo(m.lastIndexOf('>')));
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(TelegramHtmlFormatter.toMessages(null)).isEmpty();
        assertThat(TelegramHtmlFormatter.toMessages("")).isEmpty();
        assertThat(TelegramHtmlFormatter.toMessages("   ")).isEmpty();
    }

    @Test
    void escapeHandlesAmpersandBeforeAngleBrackets() {
        assertThat(TelegramHtmlFormatter.escape("a & b < c")).isEqualTo("a &amp; b &lt; c");
        assertThat(TelegramHtmlFormatter.escape(null)).isEmpty();
    }
}
