package com.lagradost.cloudstream3.utils

/**
 * Orbit's sampled subtitle language lookups, backed by the pinned CloudStream language table.
 */
object SubtitleHelper {
    private data class LanguageMetadata(
        val languageName: String,
        val nativeName: String,
        val IETF_tag: String,
        val ISO_639_1: String,
        val ISO_639_2_B: String,
        val ISO_639_3: String,
        val openSubtitles: String,
    )
    private val languages = listOf(
        // languageName, nativeName, IETF_tag, ISO_639_1, ISO_639_2_B, ISO_639_3, openSubtitles
        LanguageMetadata("Afar","Afaraf","aa","aa","aar","aar",""),
        LanguageMetadata("Afrikaans","Afrikaans","af","af","afr","afr","af"),
        LanguageMetadata("Akan","Akan","ak","ak","aka","aka",""),
        LanguageMetadata("Albanian","Shqip","sq","sq","","sqi","sq"),
        LanguageMetadata("Amharic","አማርኛ","am","am","amh","amh","am"),
        LanguageMetadata("Arabic","العربية","ar","ar","ara","ara","ar"),
        LanguageMetadata("Arabic (Levantine)","عربي شامي","apc","","ajp","apc","ar"), // "ajp" is deprecated, keeping for compatibility
        LanguageMetadata("Arabic (Najdi)","عربي شامي","ars","","","ars","ar"),
        LanguageMetadata("Aragonese","aragonés","an","an","arg","arg","an"),
        LanguageMetadata("Armenian","Հայերեն","hy","hy","","hye","hy"),
        LanguageMetadata("Assamese","অসমীয়া","as","as","asm","asm","as"),
        LanguageMetadata("Avaric","авар мацӀ, магӀарул мацӀ","av","av","ava","ava",""),
        LanguageMetadata("Aymara","aymar aru","ay","ay","aym","aym",""),
        LanguageMetadata("Azerbaijani","Azərbaycan","az","az","aze","aze","az-az"),
        LanguageMetadata("Azerbaijani (South)","Azərbaycan (Cənubi)","azb","","","azb","az-zb"),
        LanguageMetadata("Bambara","bamanankan","bm","bm","bam","bam",""),
        LanguageMetadata("Basque","euskara, euskera","eu","eu","","eus","eu"),
        LanguageMetadata("Belarusian","беларуская мова","be","be","bel","bel","be"),
        LanguageMetadata("Bengali","বাংলা","bn","bn","ben","ben","bn"),
        LanguageMetadata("Bosnian","bosanski jezik","bs","bs","bos","bos","bs"),
        LanguageMetadata("Breton","brezhoneg","br","br","bre","bre","br"),
        LanguageMetadata("Bulgarian","български език","bg","bg","bul","bul","bg"),
        LanguageMetadata("Burmese","ဗမာစာ","my","my","","mya","my"),
        LanguageMetadata("Catalan","català","ca","ca","cat","cat","ca"),
        LanguageMetadata("Chichewa","chiCheŵa, chinyanja","ny","ny","nya","nya",""),
        LanguageMetadata("Chinese","中文, 汉语, 漢語","zh","zh","chi","zho","ze"),
        LanguageMetadata("Chinese (Cantonese)","廣東話, 广东话","yue","","","yue","zh-ca"),
        LanguageMetadata("Chinese (simplified)","汉语","zh-hans","","","","zh-cn"),
        LanguageMetadata("Chinese (Taiwan)","正體中文(臺灣)","zh-hant-tw","","","","zh-tw"),
        LanguageMetadata("Chinese (traditional)","漢語","zh-hant","","","","zh-tw"),
        LanguageMetadata("Croatian","hrvatski jezik","hr","hr","hrv","hrv","hr"),
        LanguageMetadata("Czech","čeština, český jazyk","cs","cs","","ces","cs"),
        LanguageMetadata("Danish","dansk","da","da","dan","dan","da"),
        LanguageMetadata("Dari","دری","prs","","","prs","pr"),
        LanguageMetadata("Dutch","Nederlands, Vlaams","nl","nl","","nld","nl"),
        LanguageMetadata("Dzongkha","རྫོང་ཁ","dz","dz","dzo","dzo",""),
        LanguageMetadata("English","English","en","en","eng","eng","en"),
        LanguageMetadata("Esperanto","Esperanto","eo","eo","epo","epo","eo"),
        LanguageMetadata("Estonian","eesti, eesti keel","et","et","est","est","et"),
        LanguageMetadata("Ewe","Eʋegbe","ee","ee","ewe","ewe",""),
        LanguageMetadata("Extremaduran","Estremeñu","ext","","","ext","ex"),
        LanguageMetadata("Faroese","føroyskt","fo","fo","fao","fao",""),
        LanguageMetadata("Fijian","vosa Vakaviti","fj","fj","fij","fij",""),
        LanguageMetadata("Filipino","Wikang Filipino","fil","","fil","fil",""),
        LanguageMetadata("Finnish","suomi, suomen kieli","fi","fi","fin","fin","fi"),
        LanguageMetadata("French","Français","fr","fr","","fra","fr"),
        LanguageMetadata("Fula","Fulfulde, Pulaar, Pular","ff","ff","ful","ful",""),
        LanguageMetadata("Galician","Galego","gl","gl","glg","glg","gl"),
        LanguageMetadata("Ganda","Luganda","lg","lg","lug","lug",""),
        LanguageMetadata("Georgian","ქართული","ka","ka","","kat","ka"),
        LanguageMetadata("German","Deutsch","de","de","","deu","de"),
        LanguageMetadata("Greek","ελληνικά","el","el","","ell","el"),
        LanguageMetadata("Guarani","Avañe'ẽ","gn","gn","grn","gug",""),
        LanguageMetadata("Gujarati","ગુજરાતી","gu","gu","guj","guj",""),
        LanguageMetadata("Haitian","Kreyòl ayisyen","ht","ht","hat","hat",""),
        LanguageMetadata("Hausa","(Hausa) هَوُسَ","ha","ha","hau","hau",""),
        LanguageMetadata("Hebrew","עברית","he","iw","heb","heb","he"), // "iw" is deprecated, keeping for compatibility
        LanguageMetadata("Hindi","हिन्दी, हिंदी","hi","hi","hin","hin","hi"),
        LanguageMetadata("Hungarian","Magyar","hu","hu","hun","hun","hu"),
        LanguageMetadata("Icelandic","Íslenska","is","is","","isl","is"),
        LanguageMetadata("Ido","Ido","io","io","ido","ido",""),
        LanguageMetadata("Igbo","Asụsụ Igbo","ig","ig","ibo","ibo","ig"),
        LanguageMetadata("Indonesian","Bahasa Indonesia","id","in","ind","ind","id"), // "in" is deprecated, keeping for compatibility
        LanguageMetadata("Interlingua","Interlingua","ia","ia","ina","ina","ia"),
        LanguageMetadata("Interlingue","Interlingue (originally Occidental)","ie","ie","ile","ile",""),
        LanguageMetadata("Irish","Gaeilge","ga","ga","gle","gle","ga"),
        LanguageMetadata("Italian","italiano","it","it","ita","ita","it"),
        LanguageMetadata("Japanese","日本語 (にほんご)","ja","ja","jpn","jpn","ja"),
        LanguageMetadata("Javanese","ꦧꦱꦗꦮ","jv","jv","jav","jvn",""),
        LanguageMetadata("Kalaallisut","kalaallisut, kalaallit oqaasii","kl","kl","kal","kal",""),
        LanguageMetadata("Kannada","ಕನ್ನಡ","kn","kn","kan","kan","kn"),
        LanguageMetadata("Kanuri","Kanuri","kr","kr","kau","kau",""),
        LanguageMetadata("Kashmiri","कश्मीरी, كشميري‎","ks","ks","kas","kas",""),
        LanguageMetadata("Kazakh","қазақ тілі","kk","kk","kaz","kaz","kk"),
        LanguageMetadata("Khmer","ខ្មែរ, ខេមរភាសា, ភាសាខ្មែរ","km","km","khm","khm","km"),
        LanguageMetadata("Kikuyu","Gĩkũyũ","ki","ki","kik","kik",""),
        LanguageMetadata("Kinyarwanda","Ikinyarwanda","rw","rw","kin","kin",""),
        LanguageMetadata("Kirundi","Ikirundi","rn","rn","run","run",""),
        LanguageMetadata("Kongo","Kikongo","kg","kg","kon","kon",""),
        LanguageMetadata("Korean","한국어, 조선어","ko","ko","kor","kor","ko"),
        LanguageMetadata("Kurdish","Kurdî, كوردی‎","ku","ku","kur","kur","ku"),
        LanguageMetadata("Kyrgyz","Кыргызча, Кыргыз тили","ky","ky","kir","kir",""),
        LanguageMetadata("Lao","ພາສາລາວ","lo","lo","lao","lao",""),
        LanguageMetadata("Latin","Latine","la","la","lat","lat",""),
        LanguageMetadata("Latvian","latviešu valoda","lv","lv","lav","lav","lv"),
        LanguageMetadata("Lingala","Lingála","ln","ln","lin","lin",""),
        LanguageMetadata("Lithuanian","lietuvių kalba","lt","lt","lit","lit","lt"),
        LanguageMetadata("Luba-Katanga","Tshiluba","lu","lu","lub","lub",""),
        LanguageMetadata("Luxembourgish","Lëtzebuergesch","lb","lb","ltz","ltz","lb"),
        LanguageMetadata("Macedonian","македонски","mk","mk","","mkd","mk"),
        LanguageMetadata("Malagasy","fiteny malagasy","mg","mg","mlg","mlg",""),
        LanguageMetadata("Malay","Bahasa Melayu, بهاس ملايو‎","ms","ms","","msa","ms"),
        LanguageMetadata("Malayalam","മലയാളം","ml","ml","mal","mal","ml"),
        LanguageMetadata("Maltese","Malti","mt","mt","mlt","mlt",""),
        LanguageMetadata("Manx","Gaelg, Gailck","gv","gv","glv","glv",""),
        LanguageMetadata("Marathi","मराठी","mr","mr","mar","mar","mr"),
        LanguageMetadata("Marshallese","Kajin M̧ajeļ","mh","mh","mah","mah",""),
        LanguageMetadata("Meitei","ꯃꯅꯤꯄꯨꯔꯤ, মণিপুরী","mni","","mni","mni","ma"),
        LanguageMetadata("Mexican Spanish", "Español mexicano", "es-MX", "mx","","",""), // iso639_1 is not mx but, some extension use it as such
        LanguageMetadata("Mongolian","Монгол хэл","mn","mn","mon","mon","mn"),
        LanguageMetadata("Montenegrin","crnogorski, црногорски","cnr","","cnr","cnr","me"),
        LanguageMetadata("Navajo","Diné bizaad","nv","nv","nav","nav","nv"),
        LanguageMetadata("Nepali","नेपाली","ne","ne","nep","nep","ne"),
        LanguageMetadata("Northern Ndebele","isiNdebele","nd","nd","nde","nde",""),
        LanguageMetadata("Northern Sami","Davvisámegiella","se","se","sme","sme","se"),
        LanguageMetadata("Norwegian","Norsk","no","no","nor","nor","no"),
        LanguageMetadata("Norwegian Bokmål","Norsk bokmål","nb","nb","nob","nob",""),
        LanguageMetadata("Norwegian Nynorsk","Norsk nynorsk","nn","nn","nno","nno",""),
        LanguageMetadata("Nuosu","ꆈꌠ꒿ Nuosuhxop","ii","ii","iii","iii",""),
        LanguageMetadata("Occitan","occitan, lenga d'òc","oc","oc","oci","oci","oc"),
        LanguageMetadata("Oriya","ଓଡ଼ିଆ","or","or","ori","ori","or"),
        LanguageMetadata("Oromo","Afaan Oromoo","om","om","orm","orm",""),
        LanguageMetadata("Panjabi","ਪੰਜਾਬੀ, پنجابی‎","pa","pa","pan","pan",""),
        LanguageMetadata("Pashto","پښتو","ps","ps","pus","pus","ps"),
        LanguageMetadata("Persian (Farsi)","فارسی","fa","fa","","fas","fa"),
        LanguageMetadata("Polish","Polski, polszczyzna","pl","pl","pol","pol","pl"),
        LanguageMetadata("Portuguese","Português","pt","pt","por","por","pt-pt"),
        LanguageMetadata("Portuguese (Brazil)","Português (Brasil)","pt-br","","","","pt-br"),
        LanguageMetadata("Portuguese (Mozambique)","Português (Moçambique)","pt-mz","","","","pm"),
        LanguageMetadata("Quechua","Runa Simi, Kichwa","qu","qu","que","que",""),
        LanguageMetadata("Romanian","Română","ro","ro","","ron","ro"),
        LanguageMetadata("Romansh","rumantsch grischun","rm","rm","roh","roh",""),
        LanguageMetadata("Russian","Русский","ru","ru","rus","rus","ru"),
        LanguageMetadata("Samoan","gagana fa'a Samoa","sm","sm","smo","smo",""),
        LanguageMetadata("Sango","yângâ tî sängö","sg","sg","sag","sag",""),
        LanguageMetadata("Sanskrit","संस्कृतम्","sa","sa","san","san",""),
        LanguageMetadata("Santali","ᱥᱟᱱᱛᱟᱲᱤ","sat","","","sat","sx"),
        LanguageMetadata("Scottish Gaelic","Gàidhlig","gd","gd","gla","gla","gd"),
        LanguageMetadata("Serbian","српски језик","sr","sr","srp","srp","sr"),
        LanguageMetadata("Shona","chiShona","sn","sn","sna","sna",""),
        LanguageMetadata("Sindhi","सिन्धी, سنڌي، سندھی‎","sd","sd","snd","snd","sd"),
        LanguageMetadata("Sinhala","සිංහල","si","si","sin","sin","si"),
        LanguageMetadata("Slovak","slovenčina, slovenský jazyk","sk","sk","","slk","sk"),
        LanguageMetadata("Slovenian","slovenski jezik, slovenščina","sl","sl","slv","slv","sl"),
        LanguageMetadata("Somali","Soomaaliga, af Soomaali","so","so","som","som","so"),
        LanguageMetadata("Sotho","Sesotho","st","st","sot","sot",""),
        LanguageMetadata("Southern Ndebele","isiNdebele","nr","nr","nbl","nbl",""),
        LanguageMetadata("Spanish","Español","es","es","spa","spa","es"),
        LanguageMetadata("Spanish (Europe)","Español (Europa)","es-es","","","","sp"),
        LanguageMetadata("Spanish (Latin America)","Español (Latinoamérica)","es-419","","","","ea"),
        LanguageMetadata("Sundanese","Basa Sunda","su","su","sun","sun",""),
        LanguageMetadata("Swahili","Kiswahili","sw","sw","swa","swa","sw"),
        LanguageMetadata("Swedish","Svenska","sv","sv","swe","swe","sv"),
        LanguageMetadata("Tagalog","Wikang Tagalog, ᜆᜄᜎᜓᜄ᜔","tl","tl","","tlg","tl"),
        LanguageMetadata("Tajik","тоҷикӣ, toçikī, تاجیکی‎","tg","tg","tgk","tgk",""),
        LanguageMetadata("Tamil","தமிழ்","ta","ta","tam","tam","ta"),
        LanguageMetadata("Tatar","татар теле, tatar tele","tt","tt","tat","tat","tt"),
        LanguageMetadata("Telugu","తెలుగు","te","te","tel","tel","te"),
        LanguageMetadata("Tetum","Tetun","tdt","","","tdt","tm-td"),
        LanguageMetadata("Thai","ไทย","th","th","tha","tha","th"),
        LanguageMetadata("Tibetan Standard","བོད་ཡིག","bo","bo","","bod",""),
        LanguageMetadata("Tigrinya","ትግርኛ","ti","ti","tir","tir",""),
        LanguageMetadata("Toki Pona","toki pona","tok","","","tok","tp"),
        LanguageMetadata("Tonga","faka Tonga","to","to","ton","ton",""),
        LanguageMetadata("Tsonga","Xitsonga","ts","ts","tso","tso",""),
        LanguageMetadata("Tswana","Setswana","tn","tn","tsn","tsn",""),
        LanguageMetadata("Turkish","Türkçe","tr","tr","tur","tur","tr"),
        LanguageMetadata("Turkmen","Türkmen, Түркмен","tk","tk","tuk","tuk","tk"),
        LanguageMetadata("Ukrainian","Українська","uk","uk","ukr","ukr","uk"),
        LanguageMetadata("Urdu","اردو","ur","ur","urd","urd","ur"),
        LanguageMetadata("Uzbek","Oʻzbek, Ўзбек, أۇزبېك‎","uz","uz","uzb","uzb","uz"),
        LanguageMetadata("Vietnamese","Tiếng Việt","vi","vi","vie","vie","vi"),
        LanguageMetadata("Welsh","Cymraeg","cy","cy","","cym","cy"),
        LanguageMetadata("Wolof","Wollof","wo","wo","wol","wol",""),
        LanguageMetadata("Xhosa","isiXhosa","xh","xh","xho","xho",""),
        LanguageMetadata("Yoruba","Yorùbá","yo","yo","yor","yor",""),
        LanguageMetadata("Zhuang","Saɯ cueŋƅ, Saw cuengh","za","za","zha","zha",""),
        LanguageMetadata("Zulu","isiZulu","zu","zu","zul","zul",""),
    )
    private val indexMapIETF_tag =
        languages.withIndex().associate { (index, language) -> language.IETF_tag.lowercase() to index }
    private val indexMapISO_639_1 =
        languages.withIndex().associate { (index, language) -> language.ISO_639_1.lowercase() to index }
    private val indexMapISO_639_3 =
        languages.withIndex().associate { (index, language) -> language.ISO_639_3.lowercase() to index }
    private val indexMapISO_639_2_B =
        languages.withIndex().associate { (index, language) -> language.ISO_639_2_B.lowercase() to index }
    private val indexMapOpenSubtitles =
        languages.withIndex().associate { (index, language) -> language.openSubtitles.lowercase() to index }

    private fun getLanguageDataFromCode(languageCode: String?): LanguageMetadata? {
        if (languageCode.isNullOrBlank() || languageCode.length < 2) return null

        val code = languageCode.trim().lowercase()
        val index = indexMapIETF_tag[code]
            ?: indexMapISO_639_1[code]
            ?: indexMapISO_639_3[code]
            ?: indexMapISO_639_2_B[code]
            ?: indexMapOpenSubtitles[code]
            ?: return null

        return languages.getOrNull(index)
    }

    fun fromTwoLettersToLanguage(input: String?): String? =
        getLanguageDataFromCode(input)?.languageName

    fun fromTagToEnglishLanguageName(languageCode: String?): String? =
        getLanguageDataFromCode(languageCode)?.languageName

    /** Converts ISO/IETF/OpenSubtitles language codes to a canonical IETF tag. */
    fun fromCodeToLangTagIETF(languageCode: String?): String? =
        getLanguageDataFromCode(languageCode)?.IETF_tag?.takeIf { it.isNotBlank() }

    /** Converts a recognized subtitle language code to the OpenSubtitles API tag. */
    fun fromCodeToOpenSubtitlesTag(languageCode: String?): String? =
        getLanguageDataFromCode(languageCode)?.openSubtitles?.takeIf { it.isNotBlank() }
}
