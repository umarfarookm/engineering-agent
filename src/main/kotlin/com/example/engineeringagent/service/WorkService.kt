package com.example.engineeringagent.service

import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.integration.jira.JiraClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class WorkService(private val jiraClient: JiraClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun getInProgressIssues(): List<JiraIssue> {
        val issues = jiraClient.getInProgressIssues()
        log.info("Found {} active issue(s): {}", issues.size, issues.map { it.key })
        return issues
    }

    fun getIssue(key: String): JiraIssue = jiraClient.getIssue(key)
}
