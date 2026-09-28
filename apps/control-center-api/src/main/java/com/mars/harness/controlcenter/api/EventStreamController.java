package com.mars.harness.controlcenter.api;

import com.mars.harness.controlcenter.events.EventStreamService;
import com.mars.harness.controlcenter.query.RunQueryService;
import com.mars.harness.controlcenter.run.RunCoordinator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The live execution event stream of one run (Server-Sent Events). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Events", description = "Execution events: replayable, strictly sequenced, live")
public class EventStreamController {

    private final EventStreamService streams;
    private final RunQueryService queries;
    private final RunCoordinator coordinator;

    public EventStreamController(EventStreamService streams, RunQueryService queries, RunCoordinator coordinator) {
        this.streams = streams;
        this.queries = queries;
        this.coordinator = coordinator;
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to a run's execution events",
            description = "Replays every event after Last-Event-ID (or ?after=), then streams new ones. Event names: hello, "
                    + "execution-event (id = sequence), worker, heartbeat.")
    public SseEmitter events(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(required = false) Long after, HttpServletResponse response) {
        // a run this server is creating right now may not have its directory yet
        if (!queries.exists(runId) && coordinator.running(runId).isEmpty()) {
            throw ApiException.runNotFound(runId);
        }
        long cursor = after != null ? after : parse(lastEventId);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.subscribe(runId, Math.max(0, cursor));
    }

    private static long parse(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0; // an unparseable cursor replays from the start: duplicates are dropped by sequence on the client
        }
    }
}
