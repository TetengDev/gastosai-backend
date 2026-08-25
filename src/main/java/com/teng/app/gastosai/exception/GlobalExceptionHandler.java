package com.teng.app.gastosai.exception;

import com.teng.app.gastosai.service.AppEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Arrays;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Provider callbacks (today only {@code /webhooks/paymongo}); never a request from a user. */
	private static final String WEBHOOK_PATH_PREFIX = "/webhooks/";

	private final AppEventService appEventService;

	public GlobalExceptionHandler(AppEventService appEventService) {
		this.appEventService = appEventService;
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ProblemDetail> notFound(ResourceNotFoundException ex) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		pd.setTitle("Not Found");
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
	}

	@ExceptionHandler(FeatureLockedException.class)
	public ResponseEntity<ProblemDetail> featureLocked(FeatureLockedException ex) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
		pd.setTitle("Upgrade Required");
		pd.setProperty("feature", ex.getFeature().name());
		return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(pd);
	}

	@ExceptionHandler(AiQuotaExceededException.class)
	public ResponseEntity<ProblemDetail> quotaExceeded(AiQuotaExceededException ex) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
		pd.setTitle("AI Quota Exceeded");
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(pd);
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ProblemDetail> responseStatus(ResponseStatusException ex) {
		// Handle here so the response is written directly by the advice. Otherwise the exception
		// forwards to /error, which is authenticated, and surfaces to the client as a 401 (logout)
		// instead of the intended status (e.g. 409 on a duplicate budget).
		HttpStatusCode status = ex.getStatusCode();
		String detail = ex.getReason() != null ? ex.getReason() : ex.getMessage();
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
		return ResponseEntity.status(status).body(pd);
	}

	@ExceptionHandler(InvalidSignatureException.class)
	public ResponseEntity<ProblemDetail> invalidSignature(InvalidSignatureException ex) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
		pd.setTitle("Unauthorized");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
	}

	@ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
	public ResponseEntity<ProblemDetail> badRequest(RuntimeException ex) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		pd.setTitle("Bad Request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
	}

	/**
	 * 400 is a reasonable default for a user request whose query fails, and the wrong answer for a
	 * provider callback: PayMongo does not retry a 4xx, so a 400 on a verified webhook drops a paid
	 * event. {@code PaymentService} already converts the failures it can see into a 503; this is the
	 * net under it, for a data-access failure anywhere else on a callback path.
	 */
	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ProblemDetail> dataAccess(DataAccessException ex, HttpServletRequest request) {
		if (request.getRequestURI().startsWith(WEBHOOK_PATH_PREFIX)) {
			log.error("Data access error on webhook {}: {}", request.getRequestURI(), ex.getMessage(), ex);
			ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
					"Webhook could not be applied; retry the delivery.");
			pd.setTitle("Service Unavailable");
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
		}
		log.warn("Data access error: {}", ex.getMessage());
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Query execution failed");
		pd.setTitle("Bad Request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ProblemDetail> missingParam(MissingServletRequestParameterException ex) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Required parameter '" + ex.getParameterName() + "' is missing");
		pd.setTitle("Bad Request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException ex) {
		// Generic message — do not echo the raw rejected input value back to the client.
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Invalid value for parameter '" + ex.getName() + "'.");
		pd.setTitle("Bad Request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
		String detail = ex.getBindingResult().getFieldErrors().stream()
				.map(err -> err.getField() + ": " + err.getDefaultMessage())
				.collect(Collectors.joining("; "));
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
		pd.setTitle("Validation Failed");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
	}

	/**
	 * A body Jackson cannot bind — an unknown enum name, a string where a number belongs, JSON that
	 * does not parse. Without this the failure reaches the catch-all below and answers 500, because
	 * an {@code @ExceptionHandler} on this advice wins over Spring's default resolver, which would
	 * otherwise have made it a 400 on its own.
	 *
	 * <p>The detail names the offending field, and for an enum the values it accepts — both are
	 * already public in {@code openapi.json}. Jackson's own message is never echoed: it quotes the
	 * rejected input and the target class, which is the client's data and our package layout.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> unreadableBody(HttpMessageNotReadableException ex) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, describe(ex));
		pd.setTitle("Bad Request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
	}

	private static String describe(HttpMessageNotReadableException ex) {
		if (ex.getCause() instanceof InvalidFormatException cause) {
			String field = fieldPath(cause);
			Class<?> target = cause.getTargetType();
			if (field == null) {
				return "Request body could not be read.";
			}
			// getEnumConstants() rather than isEnum(): an enum whose constants have bodies is
			// compiled as anonymous subclasses, and isEnum() is false for those.
			Object[] allowed = target == null ? null : target.getEnumConstants();
			if (allowed != null) {
				return "Invalid value for field '" + field + "'. Allowed values: "
						+ Arrays.stream(allowed).map(String::valueOf).collect(Collectors.joining(", "))
						+ ".";
			}
			return "Invalid value for field '" + field + "'.";
		}
		if (ex.getCause() instanceof MismatchedInputException cause) {
			String field = fieldPath(cause);
			if (field != null) {
				return "Invalid value for field '" + field + "'.";
			}
		}
		return "Request body could not be read.";
	}

	/** Dotted path to the field Jackson choked on, e.g. {@code frequency} or {@code items[2].amount}. */
	private static String fieldPath(DatabindException ex) {
		StringBuilder path = new StringBuilder();
		for (DatabindException.Reference ref : ex.getPath()) {
			if (ref.getPropertyName() != null) {
				if (!path.isEmpty()) {
					path.append('.');
				}
				path.append(ref.getPropertyName());
			} else {
				path.append('[').append(ref.getIndex()).append(']');
			}
		}
		return path.isEmpty() ? null : path.toString();
	}

	/**
	 * Catch-all for otherwise-unhandled exceptions. Records the failure to app_event and
	 * logs the full stack (JSON-structured in prod), then returns a generic 500 without
	 * leaking internals. More specific handlers above take precedence over this one.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ProblemDetail> unhandled(Exception ex, HttpServletRequest request) {
		String path = request.getRequestURI();
		log.error("Unhandled error on {} {}: {}", request.getMethod(), path, ex.getMessage(), ex);
		appEventService.recordError(path, HttpStatus.INTERNAL_SERVER_ERROR.value(),
				ex.getMessage(), ex.getClass().getName() + ": " + ex.getMessage());
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"An unexpected error occurred.");
		pd.setTitle("Internal Server Error");
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
	}
}
