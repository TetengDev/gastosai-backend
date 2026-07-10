package com.teng.app.gastosai.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ProblemDetail> dataAccess(DataAccessException ex) {
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
}
