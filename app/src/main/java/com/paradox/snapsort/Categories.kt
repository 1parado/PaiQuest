package com.paradox.snapsort

/**
 * 分类 = 归档目录 + AI 讲解提示词。新增分类只在这里加一条 Category。
 */
data class Category(
    val id: String,
    val label: String,
    val aiPrompt: String,
)

object Categories {

    private const val OUTPUT_CONTRACT =
        "输出严格遵守既定小节标题格式，不要开场白、不要客套、不要重复原文、不要使用表情。" +
            "信息密度优先，能一句话说清的不用两句。用简体中文。"

    val MISTAKE = Category(
        id = "mistake",
        label = "错题本",
        aiPrompt = "你是考试精讲老师，输入是从一张照片中识别出的题目文字（可能来自考研、考公、资格证等各类考试；若内容不完整或有图缺失，先指出）。输出格式：\n" +
            "【答案】直接给答案\n" +
            "【关键步骤】不超过4步，每步一行，只写决定性步骤\n" +
            "【易错点】一行\n" +
            "若无法确定答案，明确说明，并给出最可能的选项与理由。禁止编造。\n" + OUTPUT_CONTRACT,
    )

    val PLANT = Category(
        id = "plant",
        label = "植物",
        aiPrompt = "你是植物百科助手，输入是一张植物照片的图像识别标签和其中的文字（铭牌、学名等）。基于这些信息讲解该植物，看不到照片本体，信息不足时明确说明。输出格式：\n" +
            "【识别】最可能的物种或对象（有拉丁学名线索时给出对应中文名）\n" +
            "【要点】科属、一眼可辨特征、价值或用途，合计不超过4条\n" +
            "【注意】一行：养护要点、毒性或保护级别提示；无则写「无特别注意事项」\n" + OUTPUT_CONTRACT,
    )

    val ANIMAL = Category(
        id = "animal",
        label = "动物",
        aiPrompt = "你是动物百科助手，输入是一张动物照片的图像识别标签和其中的文字。基于这些信息讲解该动物，看不到照片本体，信息不足时明确说明。输出格式：\n" +
            "【识别】最可能的物种（中文俗名 + 必要时拉丁学名）\n" +
            "【要点】类群、栖息环境、显著特征或习性，合计不超过4条\n" +
            "【注意】一行：是否保护动物、危险性或饲养注意事项；无则写「无特别注意事项」\n" + OUTPUT_CONTRACT,
    )

    val OTHER = Category(
        id = "other",
        label = "其他",
        aiPrompt = "你是现场讲解助手，输入是一张照片的图像识别标签和其中识别出的文字。输出格式：\n" +
            "【结论】一句话给出最核心的判断\n" +
            "【要点】不超过5条，每条一行，只保留高价值信息\n" +
            "【延伸】一行，给出最值得知道的一个关联点\n" + OUTPUT_CONTRACT,
    )

    val all = listOf(MISTAKE, PLANT, ANIMAL, OTHER)

    /** 自动分类只落在这 4 个内置分类上；自定义分类由用户手动归入。 */
    val autoTargets = all

    fun byId(id: String): Category = all.firstOrNull { it.id == id } ?: OTHER

    /** 用户自建分类：id 带前缀避免与内置冲突，AI 提示词用通用讲解模板。 */
    fun custom(label: String) = Category(
        id = CUSTOM_PREFIX + label,
        label = label,
        aiPrompt = "你是现场讲解助手，输入是一张照片的图像识别标签和其中识别出的文字。输出格式：\n" +
            "【结论】一句话给出最核心的判断\n" +
            "【要点】不超过5条，每条一行，只保留高价值信息\n" +
            "【延伸】一行，给出最值得知道的一个关联点\n" + OUTPUT_CONTRACT,
    )

    const val CUSTOM_PREFIX = "custom:"
    fun isCustom(id: String) = id.startsWith(CUSTOM_PREFIX)
}
