package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.SubmissionRequest;
import com.teng.app.gastosai.dto.SubmissionResponse;
import com.teng.app.gastosai.entity.Submission;
import com.teng.app.gastosai.entity.SubmissionType;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.SubmissionRepository;
import com.teng.app.gastosai.service.SubmissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

	@Mock SubmissionRepository submissionRepository;
	@InjectMocks SubmissionService submissionService;

	@Test
	void create_savesTrimmedAndMaps() {
		when(submissionRepository.save(any())).thenAnswer(inv -> {
			Submission s = inv.getArgument(0);
			s.setId(7L);
			return s;
		});

		SubmissionResponse resp = submissionService.create(
				new SubmissionRequest(SubmissionType.CONTACT, "  Jane  ", "jane@test.com", "  Hello  "));

		ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
		org.mockito.Mockito.verify(submissionRepository).save(captor.capture());
		Submission saved = captor.getValue();
		assertThat(saved.getType()).isEqualTo(SubmissionType.CONTACT);
		assertThat(saved.getName()).isEqualTo("Jane");
		assertThat(saved.getMessage()).isEqualTo("Hello");
		assertThat(resp.id()).isEqualTo(7L);
		assertThat(resp.handled()).isFalse();
	}

	@Test
	void create_blankOptionalFields_storedAsNull() {
		when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		submissionService.create(new SubmissionRequest(SubmissionType.SUGGESTION, "  ", "  ", "idea"));

		ArgumentCaptor<Submission> captor = ArgumentCaptor.forClass(Submission.class);
		org.mockito.Mockito.verify(submissionRepository).save(captor.capture());
		assertThat(captor.getValue().getName()).isNull();
		assertThat(captor.getValue().getEmail()).isNull();
	}

	@Test
	void list_mapsNewestFirst() {
		when(submissionRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
				Submission.builder().id(2L).type(SubmissionType.SUGGESTION).message("b").createdAt(LocalDateTime.now()).build(),
				Submission.builder().id(1L).type(SubmissionType.CONTACT).message("a").createdAt(LocalDateTime.now()).build()));

		List<SubmissionResponse> out = submissionService.list();

		assertThat(out).hasSize(2);
		assertThat(out.get(0).id()).isEqualTo(2L);
	}

	@Test
	void markHandled_setsFlag() {
		Submission s = Submission.builder().id(5L).type(SubmissionType.CONTACT).message("x").handled(false).build();
		when(submissionRepository.findById(5L)).thenReturn(java.util.Optional.of(s));
		when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		SubmissionResponse resp = submissionService.markHandled(5L);

		assertThat(resp.handled()).isTrue();
	}

	@Test
	void markHandled_notFound_throws() {
		when(submissionRepository.findById(99L)).thenReturn(java.util.Optional.empty());
		assertThatThrownBy(() -> submissionService.markHandled(99L))
				.isInstanceOf(ResourceNotFoundException.class);
	}
}
