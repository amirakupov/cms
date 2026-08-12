package com.cms.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugUtilTest {

    @Test
    void transliteratesCyrillicTitles() {
        assertThat(SlugUtil.toSlug("Профилактика сердечно-сосудистых заболеваний"))
                .isEqualTo("profilaktika-serdechno-sosudistyh-zabolevaniy");
    }

    @Test
    void handlesMixedPunctuationAndCase() {
        assertThat(SlugUtil.toSlug("Чек-ап: зачем нужны регулярные обследования?"))
                .isEqualTo("chek-ap-zachem-nuzhny-regulyarnye-obsledovaniya");
    }

    @Test
    void differentTitlesProduceDifferentSlugs() {
        String a = SlugUtil.toSlug("Стресс и его влияние на здоровье");
        String b = SlugUtil.toSlug("Вакцинация взрослых: что нужно знать");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo("post");
        assertThat(b).isNotEqualTo("post");
    }

    @Test
    void stripsLatinDiacritics() {
        assertThat(SlugUtil.toSlug("Café Déjà Vu")).isEqualTo("cafe-deja-vu");
    }

    @Test
    void fallsBackForUnslugifiableInput() {
        assertThat(SlugUtil.toSlug("!!! ??? ***")).isEqualTo("post");
        assertThat(SlugUtil.toSlug("")).isEqualTo("post");
        assertThat(SlugUtil.toSlug(null)).isEqualTo("post");
    }

    @Test
    void truncatesOnWordBoundary() {
        String slug = SlugUtil.toSlug("Современные методы диагностики заболеваний "
                + "и профилактика сердечно-сосудистых осложнений у взрослых пациентов");
        assertThat(slug).hasSizeLessThanOrEqualTo(80);
        assertThat(slug).doesNotEndWith("-");
    }
}
