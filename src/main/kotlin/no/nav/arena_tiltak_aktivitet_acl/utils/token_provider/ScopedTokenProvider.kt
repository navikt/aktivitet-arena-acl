package no.nav.arena_tiltak_aktivitet_acl.utils.token_provider

/**
 * Provides tokens that uses OAuth 2.0 scope
 * See: https://oauth.net/2/scope
 */
interface ScopedTokenProvider {
	fun getToken(scope: String): String
}
