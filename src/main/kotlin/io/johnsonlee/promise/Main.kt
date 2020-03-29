package io.johnsonlee.promise

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

private const val GITHUB_API = "https://api.github.com"

private val GITHUB_TOKEN: String = System.getProperty("GITHUB_TOKEN", System.getenv("GITHUB_TOKEN")) ?: ""

fun getStargazerUrl(user: String, repo: String, page: Int = 1)
        = URL("$GITHUB_API/repos/${user}/${repo}/stargazers?page=${page}&access_token=${GITHUB_TOKEN}").also(::println)

/**
 * Usage:
 *
 * ```bash
 * java -DGITHUB_TOKEN=<GITHUB TOKEN> -jar build/libs/promise-1.0.0-all.jar <user> <repo>
 * ```
 *
 * @author johnsonlee
 */
fun main(args: Array<String>) {
    if (args.size < 2) return

    val (user, repo) = args

    println("Fetching stargazers from ${user}/${repo} ...")

    val n = getPageCount(user, repo)()

    println("Found $n pages")

    Promise.all((1..n).map { i ->
        getStargazer(user, repo, i)
    }).then {
        println(it.size)
        it.forEachIndexed { i, json ->
            println("$i: $json")
        }
    }.catch { e ->
        e.printStackTrace()
    }
}

fun getStargazer(user: String, repo: String, page: Int) = Promise<String, Throwable> { resolve, reject ->
    try {
        val json = (getStargazerUrl(user, repo, page).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            addRequestProperty("Accept", "application/vnd.github.v3.star+json")
            addRequestProperty("Authorization", "token $GITHUB_TOKEN")
            connect()
        }.inputStream.bufferedReader().use {
            it.readText()
        }

        resolve(json)
    } catch (e: Throwable) {
        reject(e)
    }
}

fun getPageCount(user: String, repo: String) = Promise<Int, Throwable> { resolve, reject ->
    try {
        val pages = getStargazerUrl(user, repo).openConnection().apply(URLConnection::connect).run {
            try {
                getHeaderField("Link")
            } finally {
                (this as HttpURLConnection).disconnect()
            }
        }?.let { link ->
            Regex("page=\\d+").findAll(link).map {
                it.value.substringAfterLast('=').toInt()
            }.max() ?: 0
        } ?: 0
        resolve(pages)
    } catch (e: Throwable) {
        reject(e)
    }
}