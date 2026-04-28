package com.jobScheduling.job.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jobs/{jobId}/webhooks")
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookDeliveryRepository deliveryRepository;

    public WebhookController(WebhookService webhookService,
                             WebhookDeliveryRepository deliveryRepository) {
        this.webhookService = webhookService;
        this.deliveryRepository = deliveryRepository;
    }

    /** Register a new webhook for a job */
    @PostMapping
    public ResponseEntity<Webhook> register(
            @PathVariable Long jobId,
            @Valid @RequestBody RegisterWebhookRequest req) {
        Webhook wh = webhookService.register(
                jobId, req.url(), req.secretKey(), req.eventFilter());
        return ResponseEntity.status(HttpStatus.CREATED).body(wh);
    }

    /** List all webhooks for a job */
    @GetMapping
    public ResponseEntity<List<Webhook>> list(@PathVariable Long jobId) {
        return ResponseEntity.ok(webhookService.listForJob(jobId));
    }

    /** Deactivate a webhook */
    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Void> deactivate(
            @PathVariable Long jobId,
            @PathVariable Long webhookId) {
        webhookService.deactivate(webhookId);
        return ResponseEntity.noContent().build();
    }

    /** Paginated delivery history for a specific webhook */
    @GetMapping("/{webhookId}/deliveries")
    public ResponseEntity<Page<WebhookDelivery>> deliveries(
            @PathVariable Long jobId,
            @PathVariable Long webhookId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(deliveryRepository.findByWebhookId(webhookId, pageable));
    }

    /** All deliveries across all webhooks for a job (for the dashboard) */
    @GetMapping("/deliveries")
    public ResponseEntity<Page<WebhookDelivery>> allDeliveries(
            @PathVariable Long jobId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(deliveryRepository.findByJobId(jobId, pageable));
    }

    // ── Request DTO ──────────────────────────────────────────────────────────
    public record RegisterWebhookRequest(
            @NotBlank String url,
            String secretKey,
            @Pattern(regexp = "ALL|STARTED|SUCCESS|FAILED") String eventFilter
    ) {}
}
