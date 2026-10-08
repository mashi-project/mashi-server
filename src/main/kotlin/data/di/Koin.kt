package com.mashiverse.data.di

import com.mashiverse.data.db.daos.HistoryDao
import com.mashiverse.data.db.daos.ImageDao
import com.mashiverse.data.db.daos.UserDao
import com.mashiverse.data.remote.KtorClient
import com.mashiverse.data.remote.apis.IpfsApi
import com.mashiverse.data.repos.ImageRepo
import com.mashiverse.images.playwright.combiners.AnimCombiner
import com.mashiverse.images.playwright.combiners.CompositeCombiner
import com.mashiverse.services.AnimService
import images.services.ImageService
import io.ktor.client.*
import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(module {
            single<AnimCombiner> { AnimCombiner() }

            single<ImageRepo> { ImageRepo() }

            single<ImageService> { ImageService() }

            factory<UserDao> { UserDao() }

            factory<ImageDao> { ImageDao() }

            single<CompositeCombiner> { CompositeCombiner() }

            factory<IpfsApi> { IpfsApi() }

            single<HttpClient> { KtorClient.client() }

            single<ImageRepo> { ImageRepo() }

            single<AnimService> { AnimService() }

            single<HistoryDao> { HistoryDao() }
        })
    }
}
