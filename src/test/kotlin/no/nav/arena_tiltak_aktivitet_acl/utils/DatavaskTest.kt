package no.nav.arena_tiltak_aktivitet_acl.utils

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DatavaskTest : FunSpec({

	test("removeNullCharacters - should remove null characters") {
		"\u0000test\u0000".removeNullCharacters() shouldBe "test"
	}

	test("removeNullCharacters - should remove escaped null characters") {
		"\\u0000test\\u0000".removeNullCharacters() shouldBe "test"
	}

})
