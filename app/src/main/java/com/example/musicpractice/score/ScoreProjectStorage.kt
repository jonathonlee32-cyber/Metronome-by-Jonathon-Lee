package com.example.musicpractice.score

/**
 * 乐谱库"存在哪儿"的抽象。
 *
 * [ScoreLibraryManager]（管项目、管最近打开、管翻到第几页）只跟这个接口打交道，
 * 不关心底下是数据库还是文件。这样一来：
 *
 * - App 里用的是 Room 数据库（[ScoreProjectDatabaseStorage]）—— 需求一要求"用 Android
 *   本地数据库保存"，关掉 App、手机重启之后数据都还在；
 * - 单元测试里塞一个临时文件（[ScoreLibraryStore]）就能验证全部逻辑，不需要模拟器；
 * - v4.1 及更早版本存在 JSON 文件里的老乐谱库，也靠 [ScoreLibraryStore] 读出来迁移进数据库。
 *
 * 写的时候按"改了哪一块就存哪一块"分开：[saveProject] 只写项目本身（例如翻页改了阅读进度）、
 * [saveImages] 只在图片或排序变了时写图片、[deleteProject] 只删那一条。翻一页不会去重写
 * 所有项目的所有图片 —— 数据库里这是很自然的做法，而在 JSON 文件时代只能整份重写。
 *
 * 所有方法都是同步的（调用返回 = 已经落盘），内部会自己做磁盘 / 数据库 IO，
 * 所以**调用返回之后数据一定存住了**，App 被强杀也不会丢。
 */
interface ScoreProjectStorage {

    /** 读出全部项目；读不出（第一次运行、数据被改坏）时返回空乐谱库，不抛异常。 */
    fun read(): ScoreLibrary

    /**
     * 整份覆盖（只用在"把老乐谱库迁移进数据库"这一件事上）。
     *
     * 迁移以外的场景一律用下面几个按块写的方法。
     */
    fun replaceAll(library: ScoreLibrary)

    /** 写入 / 更新一个项目（连它自己的字段：名字、页码、创建时间、最近打开时间……）。 */
    fun saveProject(project: ScoreProject)

    /** 写入一个图片项目的图片列表（导入顺序 + 每张图的排序页码）。 */
    fun saveImages(projectId: String, images: List<ScoreImage>)

    /** 记住"最近打开的是哪个项目"；null 表示一份都没有。 */
    fun saveLastOpened(projectId: String?)

    /** 删掉一个项目 **以及它的图片记录**（需求五；用户原来的文件不归这里管）。 */
    fun deleteProject(projectId: String)
}
