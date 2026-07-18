/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
pluginManagement {
    repositories {
        // Content filters keep non-Google artifacts (kotlinx, koin, …) from ever
        // being requested from dl.google.com — resolution stays correct even when
        // that host is briefly unreachable.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "latentjam-monorepo"

include(":core:smart")
include(":core:library")
include(":core:playback")
include(":composeApp")
include(":androidApp")
