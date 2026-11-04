package io.github.mangi.eta.agent.style

/**
 * 通知风格：只影响**语气**，不影响事实、安全规则和工具权限。
 *
 * 为什么单独做一个词条，而不是直接复用「角色」：角色卡会把系统提示替换成人物设定，
 * 用在日常问答和定时任务通知里副作用太大（会串味、丢规则）。这里只往系统提示加一句语气要求。
 *
 * @property instruction 给模型的语气指令；空串表示不加任何语气要求
 * @property seed 是不是首次安装时预置的（只是来源标记，用户照样可以改和删）
 */
internal data class SpeechStyle(
    val id: String,
    val name: String,
    val instruction: String,
    val seed: Boolean = false,
    /** 自定义头像文件路径；为空时界面用调色板图标。 */
    val avatarPath: String? = null,
    /** 卡片底部的小标签，最多 [MAX_TAGS] 个，每个不超过 [MAX_TAG_CHARS] 字。 */
    val tags: List<String> = emptyList(),
) {
    companion object {
        /** 默认风格的 id：列表被删空时要把它补回来，否则没有可选风格。 */
        const val NATURAL_ID = "natural"

        const val MAX_NAME_CHARS = 16
        const val MAX_INSTRUCTION_CHARS = 300
        const val MAX_STYLES = 30
        const val MAX_TAGS = 3
        const val MAX_TAG_CHARS = 8

        /** 首次安装时的种子风格。**全部可改可删**，只作为起点。 */
        val SEEDS: List<SpeechStyle> = listOf(
            SpeechStyle(NATURAL_ID, "自然", "", seed = true),
            SpeechStyle(
                id = "concise",
                name = "简洁",
                instruction = "回答尽量短：先给结论，一句话说清；不寒暄、不复述用户的话、不列没必要的清单。",
                seed = true,
            ),
            SpeechStyle(
                id = "cute",
                name = "可爱",
                instruction = "语气轻松可爱，可以适当用「呀」「啦」「～」这类词，但不要卖萌到影响信息准确；" +
                    "报错、失败、隐私、安全这类内容保持正经。",
                seed = true,
            ),
            SpeechStyle(
                id = "humor",
                name = "幽默",
                instruction = "可以适度开玩笑、用一点反讽，但结论必须准确；" +
                    "涉及失败、报错、金钱、隐私时不要开玩笑。",
                seed = true,
            ),
            SpeechStyle(
                id = "professional",
                name = "专业",
                instruction = "保持专业克制：先结论后依据，用「；」分隔要点，不用语气词和感叹号。",
                seed = true,
            ),
        )

        /**
         * 推荐模板：不自动写进列表，用户点一下才添加。
         *
         * 这样既给出「不知道怎么写」时的参照，又不会把列表塞满一堆根本没人用的条目。
         */
        val TEMPLATES: List<Pair<String, String>> = listOf(
            "邻家妹妹" to "用「呀」「啦」「～」，句子短一点，别卖萌到影响信息准确。",
            "损友" to "可以吐槽、偶尔拆台，但结论必须准确；涉及钱、隐私、失败时正经点。",
            "老学究" to "先讲依据再给结论，用词严谨，不用感叹号和表情。",
            "效率狂" to "只给结论和下一步，不解释过程；能用一句话就不用两句。",
            "幼儿园老师" to "用最简单的词解释，多用生活化的比喻，不用术语。",
            "军师" to "先说风险再说方案，条理清楚，给出取舍而不是只给一个答案。",
            "二次元" to "偶尔用「诶嘿」「的说」这类口癖，不堆砌；信息准确优先。",
            "冷面吐槽" to "简短、克制，偶尔一句反讽；不夸张、不卖萌。",
            "新闻播报" to "客观陈述，最要紧的先说，不加个人评价。",
            "贴心助理" to "先确认需求再动手，结尾给一句「要不要我顺手…」的具体建议。",
        )

        /**
         * 把用户输入的一串标签拆开：支持逗号、中文逗号、顿号、空格分隔。
         *
         * 顺手去重、截断到 [MAX_TAGS] 个、单个超长直接截掉 —— 标签只用于卡片展示，
         * 不该因为用户多打了两个逗号就报错。
         */
        fun parseTags(raw: String): List<String> = raw
            .split(',', '，', '、', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS)
            .map { it.take(MAX_TAG_CHARS) }

        /** 校验用户填的名字与描述；返回错误文案（null 表示通过）。 */
        fun validate(name: String, instruction: String): String? = when {
            name.trim().isEmpty() -> "名字不能为空"
            name.trim().length > MAX_NAME_CHARS -> "名字最多 $MAX_NAME_CHARS 个字"
            instruction.trim().length > MAX_INSTRUCTION_CHARS -> "风格描述最多 $MAX_INSTRUCTION_CHARS 个字"
            else -> null
        }

        /** 生成一个稳定 id（种子 id 与自建 id 不冲突）。 */
        fun newId(seed: String): String = "style-${seed.hashCode().toUInt().toString(16)}"
    }
}
