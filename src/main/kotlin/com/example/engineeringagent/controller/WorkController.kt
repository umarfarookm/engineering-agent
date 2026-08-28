package com.example.engineeringagent.controller

import com.example.engineeringagent.domain.JiraIssue
import com.example.engineeringagent.domain.DailyReport
import com.example.engineeringagent.domain.DailyWorkContext
import com.example.engineeringagent.domain.DailyWorkSummary
import com.example.engineeringagent.domain.EngineeringContext
import com.example.engineeringagent.domain.TicketActivity
import com.example.engineeringagent.service.ActivityService
import com.example.engineeringagent.service.ContextService
import com.example.engineeringagent.service.SummaryService
import com.example.engineeringagent.service.WorkService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/work")
class WorkController(
    private val workService: WorkService,
    private val activityService: ActivityService,
    private val contextService: ContextService,
    private val summaryService: SummaryService,
) {

    @GetMapping("/in-progress")
    fun inProgress(): InProgressResponse {
        val issues = workService.getInProgressIssues()
        return InProgressResponse(count = issues.size, issues = issues)
    }

    @GetMapping("/issue/{key}")
    fun issue(@PathVariable key: String): JiraIssue = workService.getIssue(key)

    /**
     * GitHub activity matched to a ticket. `detail=true` additionally loads commits, changed files
     * and reviews for confirmed matches.
     */
    @GetMapping("/activity/{key}")
    fun activity(
        @PathVariable key: String,
        @RequestParam(defaultValue = "false") detail: Boolean,
    ): TicketActivity = activityService.getActivity(key, withDetail = detail)

    /** Assembled evidence for every active ticket — the input the AI layer will reason over. */
    @GetMapping("/context")
    fun dailyContext(): DailyWorkContext = contextService.getDailyContext()

    @GetMapping("/context/{key}")
    fun context(@PathVariable key: String): EngineeringContext = contextService.getContext(key)

    /** Reasoned summary for every active ticket. */
    @PostMapping("/analyze")
    fun analyze(): DailyReport = summaryService.dailyReport()

    @PostMapping("/summary/{key}")
    fun summary(@PathVariable key: String): DailyWorkSummary = summaryService.summarize(key)
}

data class InProgressResponse(
    val count: Int,
    val issues: List<JiraIssue>,
)
