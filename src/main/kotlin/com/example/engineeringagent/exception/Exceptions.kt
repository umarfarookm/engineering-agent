package com.example.engineeringagent.exception

/** Failure states the workflow can report on without collapsing entirely. See ARCHITECTURE.md §9. */
enum class FailureState {
    JIRA_UNAVAILABLE,
    JIRA_NOT_CONFIGURED,
    GITHUB_UNAVAILABLE,
    SLACK_UNAVAILABLE,
    AI_UNAVAILABLE,
    NO_MATCHING_REPOSITORY,
    NO_ACTIVITY,
    INSUFFICIENT_CONTEXT,
}

open class EngineeringAgentException(
    val state: FailureState,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class JiraUnavailableException(message: String, cause: Throwable? = null) :
    EngineeringAgentException(FailureState.JIRA_UNAVAILABLE, message, cause)

class JiraNotConfiguredException(message: String) :
    EngineeringAgentException(FailureState.JIRA_NOT_CONFIGURED, message)

class IssueNotFoundException(key: String) :
    EngineeringAgentException(FailureState.NO_ACTIVITY, "Issue $key was not found or is not visible to this account")

class GitHubUnavailableException(message: String, cause: Throwable? = null) :
    EngineeringAgentException(FailureState.GITHUB_UNAVAILABLE, message, cause)

class GitHubNotConfiguredException(message: String) :
    EngineeringAgentException(FailureState.GITHUB_UNAVAILABLE, message)

class AiUnavailableException(message: String, cause: Throwable? = null) :
    EngineeringAgentException(FailureState.AI_UNAVAILABLE, message, cause)

class SlackUnavailableException(message: String, cause: Throwable? = null) :
    EngineeringAgentException(FailureState.SLACK_UNAVAILABLE, message, cause)

class SlackNotConfiguredException(message: String) :
    EngineeringAgentException(FailureState.SLACK_UNAVAILABLE, message)
