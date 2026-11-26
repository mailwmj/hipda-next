package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelRenderer
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl

/** 绑定 `E5`/`E6`/`M7-0 #11` 共用的表情面板插槽 [QuickReplyEmojiPanelRenderer]，零耦合启用表情与键盘等高互换。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ComposeEmojiModule {

    @Binds
    @Singleton
    abstract fun bindQuickReplyEmojiPanelRenderer(impl: EmojiPanelRendererImpl): QuickReplyEmojiPanelRenderer
}
