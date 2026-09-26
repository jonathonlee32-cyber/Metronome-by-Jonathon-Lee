package com.example.musicpractice.score

/**
 * 图片排序的全部规则。
 *
 * 排序在这里就是一件事：**给图片发页码**。每张图要么还没排（`pageNumber == null`），
 * 要么已经排到第 N 页。规则集中在这个纯逻辑对象里，所以可以直接用单元测试验证
 * （"下一个该给几号""点一下之后顺序对不对""重置以后回到导入顺序"）。
 *
 * 两个容易混的概念，这里分清楚：
 * - **存的顺序**（`project.images`）：永远是导入顺序。排序页、缩略图总览、排序用的图片查看器
 *   都按它排，所以"点序号 → 自动下一张"是一条稳定的线（图片A → 图片B → 图片C），
 *   不会因为刚排好一张图就整页跳动；
 * - **看的顺序**（[ordered]）：已经排好页码的按 1、2、3 排前面，没排序的按导入顺序跟在后面。
 *   阅读页显示的是这个顺序（需求七："排序完成后按照新的顺序显示"）。
 */
object ScoreImageOrdering {

    /**
     * 阅读顺序：已定页码按页码升序在前，未定页码按原来的先后排在后面。
     *
     * 一张都没排时，结果就等于导入顺序 —— 也就是"图片项目初始按系统返回顺序排列"。
     */
    fun ordered(images: List<ScoreImage>): List<ScoreImage> =
        orderedWithPositions(images).map { it.first }

    /**
     * 阅读顺序，同时给出每张图在**导入顺序**里的位置。
     *
     * 位置拿来定位本地副本（文件名用的是导入位置），所以排序排来排去都不影响文件在哪。
     */
    fun orderedWithPositions(images: List<ScoreImage>): List<Pair<ScoreImage, Int>> =
        images.withIndex()
            .sortedWith(compareBy({ it.value.pageNumber ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value to it.index }

    /** 已经排好页码的图片数量。 */
    fun assignedCount(images: List<ScoreImage>): Int = images.count { it.pageNumber != null }

    /**
     * 下一张待排序的图片会拿到几号。
     *
     * 例如已经排了 1、2、3，那么所有还没排序的图片右上角都显示 4 ——
     * 点哪张，哪张就是第 4 页（需求五）。
     */
    fun nextPageNumber(images: List<ScoreImage>): Int = assignedCount(images) + 1

    /**
     * 把第 [index] 张图排进下一页；已经排过的图片原样不动。
     *
     * 索引是 `project.images`（导入顺序）里的位置，和排序页看到的顺序一致。
     */
    fun assignNext(images: List<ScoreImage>, index: Int): List<ScoreImage> {
        if (index !in images.indices) return images
        if (images[index].pageNumber != null) return images
        val next = nextPageNumber(images)
        return images.mapIndexed { position, image ->
            if (position == index) image.copy(pageNumber = next) else image
        }
    }

    /** 清掉所有页码，回到导入顺序（排序页菜单里的"重置排序"）。 */
    fun reset(images: List<ScoreImage>): List<ScoreImage> =
        images.map { it.copy(pageNumber = null) }

    /** 是否每一张都已经排好页码（排序页据此显示"已排序 x / y"）。 */
    fun isComplete(images: List<ScoreImage>): Boolean =
        images.isNotEmpty() && assignedCount(images) == images.size
}
