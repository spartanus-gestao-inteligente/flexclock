package br.com.flexclock.pause;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import br.com.flexclock.model.AgentPause;

/**
 * Proves the continuation detector: an answer observed after the pause start resumes that pause once,
 * and a disabled detector or a pause without an answer never resumes anything (decision 0034;
 * requirement RF-036).
 */
class PauseContinuationPollerTest {

	private static final Instant T0 = Instant.parse("2026-11-11T02:00:00Z");

	@Test
	void anObservedAnswerResumesThePauseAtTheAnswerInstant() {
		PauseService pauseService = mock(PauseService.class);
		AgentPause pause = pause();
		when(pauseService.openPauses()).thenReturn(List.of(pause));
		when(pauseService.continuationAnswer(pause)).thenReturn(Optional.of(T0.plusSeconds(45)));

		new PauseContinuationPoller(pauseService, true).poll();

		verify(pauseService).resume(7L, T0.plusSeconds(45));
	}

	@Test
	void aPauseWithoutAnAnswerIsNeverResumed() {
		PauseService pauseService = mock(PauseService.class);
		AgentPause pause = pause();
		when(pauseService.openPauses()).thenReturn(List.of(pause));
		when(pauseService.continuationAnswer(pause)).thenReturn(Optional.empty());

		new PauseContinuationPoller(pauseService, true).poll();

		verify(pauseService, never()).resume(anyLong(), any());
	}

	@Test
	void aDisabledDetectorDoesNothing() {
		PauseService pauseService = mock(PauseService.class);

		new PauseContinuationPoller(pauseService, false).poll();

		verify(pauseService, never()).openPauses();
	}

	private AgentPause pause() {
		AgentPause pause = AgentPause.open(7L, "ses_01", T0);
		pause.setId(7L);
		return pause;
	}
}
