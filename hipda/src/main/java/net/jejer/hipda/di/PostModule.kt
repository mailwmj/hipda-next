package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.data.post.DefaultImageUploadRepository
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.ImageUploadRepository
import net.jejer.hipda.data.post.PostRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class PostModule {

    @Binds
    @Singleton
    abstract fun bindPostRepository(impl: DefaultPostRepository): PostRepository

    @Binds
    @Singleton
    abstract fun bindImageUploadRepository(impl: DefaultImageUploadRepository): ImageUploadRepository
}
