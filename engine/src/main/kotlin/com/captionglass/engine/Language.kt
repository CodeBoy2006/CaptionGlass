package com.captionglass.engine

/** Fixed model language names; never interpolate free-form UI input into a prompt. */
enum class Language(val code: String, val label: String, val promptName: String, val chineseName: String) {
    ZH("zh", "中文", "Chinese", "中文"), EN("en", "English", "English", "英语"), JA("ja", "日本語", "Japanese", "日语"),
    ZH_HANT("zh-Hant", "繁體中文", "Traditional Chinese", "繁体中文"), KO("ko", "한국어", "Korean", "韩语"),
    FR("fr", "Français", "French", "法语"), DE("de", "Deutsch", "German", "德语"), ES("es", "Español", "Spanish", "西班牙语"),
    PT("pt", "Português", "Portuguese", "葡萄牙语"), IT("it", "Italiano", "Italian", "意大利语"), RU("ru", "Русский", "Russian", "俄语"),
    TH("th", "ไทย", "Thai", "泰语"), VI("vi", "Tiếng Việt", "Vietnamese", "越南语"), ID("id", "Bahasa Indonesia", "Indonesian", "印尼语"),
    MS("ms", "Bahasa Melayu", "Malay", "马来语"), AR("ar", "العربية", "Arabic", "阿拉伯语"), TR("tr", "Türkçe", "Turkish", "土耳其语"),
    TL("tl", "Filipino", "Filipino", "菲律宾语"), HI("hi", "हिन्दी", "Hindi", "印地语"), PL("pl", "Polski", "Polish", "波兰语"),
    CS("cs", "Čeština", "Czech", "捷克语"), NL("nl", "Nederlands", "Dutch", "荷兰语"), KM("km", "ខ្មែរ", "Khmer", "高棉语"),
    MY("my", "မြန်မာ", "Burmese", "缅甸语"), FA("fa", "فارسی", "Persian", "波斯语"), GU("gu", "ગુજરાતી", "Gujarati", "古吉拉特语"),
    UR("ur", "اردو", "Urdu", "乌尔都语"), TE("te", "తెలుగు", "Telugu", "泰卢固语"), MR("mr", "मराठी", "Marathi", "马拉地语"),
    HE("he", "עברית", "Hebrew", "希伯来语"), BN("bn", "বাংলা", "Bengali", "孟加拉语"), TA("ta", "தமிழ்", "Tamil", "泰米尔语"),
    UK("uk", "Українська", "Ukrainian", "乌克兰语"), BO("bo", "བོད་སྐད་", "Tibetan", "藏语"), KK("kk", "Қазақша", "Kazakh", "哈萨克语"),
    MN("mn", "Монгол", "Mongolian", "蒙古语"), UG("ug", "ئۇيغۇرچە", "Uyghur", "维吾尔语"), YUE("yue", "粵語", "Cantonese", "粤语"),
    AZ("az", "Azərbaycanca", "Azerbaijani", "阿塞拜疆语"), BG("bg", "Български", "Bulgarian", "保加利亚语"),
    CA("ca", "Català", "Catalan", "加泰罗尼亚语"), DA("da", "Dansk", "Danish", "丹麦语"),
    EL("el", "Ελληνικά", "Greek", "希腊语"), FI("fi", "Suomi", "Finnish", "芬兰语"),
    HR("hr", "Hrvatski", "Croatian", "克罗地亚语"), HU("hu", "Magyar", "Hungarian", "匈牙利语"),
    LO("lo", "ລາວ", "Lao", "老挝语"), NO("no", "Norsk", "Norwegian", "挪威语"),
    RO("ro", "Română", "Romanian", "罗马尼亚语"), SK("sk", "Slovenčina", "Slovak", "斯洛伐克语"),
    SL("sl", "Slovenščina", "Slovenian", "斯洛文尼亚语"), SV("sv", "Svenska", "Swedish", "瑞典语"),
    UZ("uz", "Oʻzbekcha", "Uzbek", "乌兹别克语"), MK("mk", "Македонски", "Macedonian", "马其顿语"),
    ET("et", "Eesti", "Estonian", "爱沙尼亚语"), LV("lv", "Latviešu", "Latvian", "拉脱维亚语"),
    LT("lt", "Lietuvių", "Lithuanian", "立陶宛语"), MT("mt", "Malti", "Maltese", "马耳他语");

    companion object {
        fun fromCode(code: String?): Language? = entries.find { it.code == code }
    }
}

data class LanguagePair(val source: Language = Language.EN, val target: Language = Language.ZH) {
    init { require(source != target) { "Source and target must differ" } }
    fun withSource(language: Language) = LanguagePair(language, if (language == target) source else target)
    fun swapped() = LanguagePair(target, source)
}
