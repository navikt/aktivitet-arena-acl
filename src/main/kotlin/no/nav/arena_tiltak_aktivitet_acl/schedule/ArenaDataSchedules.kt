package no.nav.arena_tiltak_aktivitet_acl.schedule

import io.getunleash.Unleash
import no.nav.arena_tiltak_aktivitet_acl.repositories.ArenaDataRepository
import no.nav.arena_tiltak_aktivitet_acl.services.RetryArenaMessageProcessorService
import no.nav.arena_tiltak_aktivitet_acl.utils.AT_MIDNIGHT
import no.nav.arena_tiltak_aktivitet_acl.utils.ONE_HOUR
import no.nav.arena_tiltak_aktivitet_acl.utils.ONE_MINUTE
import no.nav.common.job.JobRunner
import no.nav.common.job.leader_election.LeaderElectionClient
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
open class ArenaDataSchedules(
	private val retryArenaMessageProcessorService: RetryArenaMessageProcessorService,
	private val arenaDataRepository: ArenaDataRepository,
	private val leaderElectionClient: LeaderElectionClient,
	private val unleash: Unleash
) {

	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelay = 10 * 1000L, initialDelay = ONE_MINUTE)
	open fun processArenaMessages() {
		if (leaderElectionClient.isLeader && unleash.isEnabled("aktivitet-arena-acl.batch.enabled")) {
			JobRunner.run("process_arena_messages", retryArenaMessageProcessorService::processMessages)
		}
	}

	@Scheduled(cron = AT_MIDNIGHT)
	open fun processFailedArenaMessages() {
		if (leaderElectionClient.isLeader && unleash.isEnabled("aktivitet-arena-acl.batch.enabled")) {
			JobRunner.run("process_failed_arena_messages", retryArenaMessageProcessorService::processFailedMessages)
		}
	}

	@Scheduled(fixedDelay = ONE_HOUR, initialDelay = ONE_MINUTE)
	open fun deleteIgnoredArenaData() {
		if (leaderElectionClient.isLeader && unleash.isEnabled("aktivitet-arena-acl.batch.enabled")) {
			JobRunner.run("delete_ignored_data") {
				val rowsDeleted = arenaDataRepository.deleteAllIgnoredData()
				log.info("Slettet ignorert data fra arena_data rows=${rowsDeleted}")
			}
		}
	}

}
