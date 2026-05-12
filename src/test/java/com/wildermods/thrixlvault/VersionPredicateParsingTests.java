package com.wildermods.thrixlvault;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.wildermods.thrixlvault.exception.VersionParsingException;
import com.wildermods.thrixlvault.utils.version.VersionInterval;
import com.wildermods.thrixlvault.utils.version.VersionParser;
import com.wildermods.thrixlvault.utils.version.VersionPredicate;

class VersionPredicateParserTest {

	@Test
	void testAnyPredicate() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("*");

		assertTrue(predicate.test(VersionParser.parse("1.0.0", false)));
		assertTrue(predicate.test(VersionParser.parse("999.999.999", false)));

		VersionInterval interval = predicate.getInterval();

		assertNull(interval.getMin());
		assertNull(interval.getMax());
	}

	@Test
	void testExactSemanticVersion() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("1.2.3");

		assertTrue(predicate.test(VersionParser.parse("1.2.3", false)));

		assertFalse(predicate.test(VersionParser.parse("1.2.4", false)));
		assertFalse(predicate.test(VersionParser.parse("1.2.2", false)));
	}

	@Test
	void testGreaterThan() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse(">1.2.3");

		assertTrue(predicate.test(VersionParser.parse("1.2.4", false)));
		assertTrue(predicate.test(VersionParser.parse("2.0.0", false)));

		assertFalse(predicate.test(VersionParser.parse("1.2.3", false)));
		assertFalse(predicate.test(VersionParser.parse("1.2.2", false)));
	}

	@Test
	void testGreaterThanOrEqual() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse(">=1.2.3");

		assertTrue(predicate.test(VersionParser.parse("1.2.3", false)));
		assertTrue(predicate.test(VersionParser.parse("1.2.4", false)));

		assertFalse(predicate.test(VersionParser.parse("1.2.2", false)));
	}

	@Test
	void testLessThan() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("<1.2.3");

		assertTrue(predicate.test(VersionParser.parse("1.2.2", false)));

		assertFalse(predicate.test(VersionParser.parse("1.2.3", false)));
		assertFalse(predicate.test(VersionParser.parse("1.2.4", false)));
	}

	@Test
	void testLessThanOrEqual() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("<=1.2.3");

		assertTrue(predicate.test(VersionParser.parse("1.2.2", false)));
		assertTrue(predicate.test(VersionParser.parse("1.2.3", false)));

		assertFalse(predicate.test(VersionParser.parse("1.2.4", false)));
	}

	@Test
	void testMultiPredicate() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse(">=1.2.0 <2.0.0");

		assertTrue(predicate.test(VersionParser.parse("1.2.0", false)));
		assertTrue(predicate.test(VersionParser.parse("1.5.0", false)));
		assertTrue(predicate.test(VersionParser.parse("1.999.999", false)));

		assertFalse(predicate.test(VersionParser.parse("1.1.9", false)));
		assertFalse(predicate.test(VersionParser.parse("2.0.0", false)));
	}

	@Test
	void testWildcardMajor() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("1.x");

		assertTrue(predicate.test(VersionParser.parse("1.0.0", false)));
		assertTrue(predicate.test(VersionParser.parse("1.5.2", false)));
		assertTrue(predicate.test(VersionParser.parse("1.999.999", false)));

		assertFalse(predicate.test(VersionParser.parse("2.0.0", false)));
		assertFalse(predicate.test(VersionParser.parse("0.9.9", false)));
	}

	@Test
	void testWildcardMinor() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("1.2.x");

		assertTrue(predicate.test(VersionParser.parse("1.2.0", false)));
		assertTrue(predicate.test(VersionParser.parse("1.2.99", false)));

		assertFalse(predicate.test(VersionParser.parse("1.3.0", false)));
		assertFalse(predicate.test(VersionParser.parse("2.2.0", false)));
	}

	@Test
	void testWildcardRequiresEqualityOperator() {
		assertThrows(
				VersionParsingException.class,
				() -> VersionPredicate.parse(">1.x")
		);

		assertThrows(
				VersionParsingException.class,
				() -> VersionPredicate.parse("<1.2.x")
		);
	}

	@Test
	void testNonSemanticExclusiveRangeFails() {
		assertThrows(
				VersionParsingException.class,
				() -> VersionPredicate.parse(">banana")
		);

		assertThrows(
				VersionParsingException.class,
				() -> VersionPredicate.parse("<apple")
		);
	}

	@Test
	void testNonSemanticEqualityWorks() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("banana");

		assertTrue(predicate.test(VersionParser.parse("banana", false)));
		assertFalse(predicate.test(VersionParser.parse("apple", false)));
	}

	@Test
	void testIntervalForExactVersion() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("1.2.3");

		VersionInterval interval = predicate.getInterval();

		assertEquals(
				VersionParser.parse("1.2.3", false),
				interval.getMin()
		);

		assertEquals(
				VersionParser.parse("1.2.3", false),
				interval.getMax()
		);

		assertTrue(interval.isMinInclusive());
		assertTrue(interval.isMaxInclusive());
	}

	@Test
	void testIntervalForRange() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse(">=1.0.0 <2.0.0");

		VersionInterval interval = predicate.getInterval();

		assertEquals(
				VersionParser.parse("1.0.0", false),
				interval.getMin()
		);

		assertEquals(
				VersionParser.parse("2.0.0", false),
				interval.getMax()
		);

		assertTrue(interval.isMinInclusive());
		assertFalse(interval.isMaxInclusive());
	}

	@Test
	void testEmptyInputBecomesAnyPredicate() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("");

		assertTrue(predicate.test(VersionParser.parse("1.0.0", false)));
		assertTrue(predicate.test(VersionParser.parse("banana", false)));
	}

	@Test
	void testWhitespaceOnlyPredicateBecomesAnyPredicate() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("     ");

		assertTrue(predicate.test(VersionParser.parse("1.0.0", false)));
	}

	@Test
	void testNullVersionThrows() throws Exception {
		VersionPredicate predicate = VersionPredicate.parse("1.0.0");

		assertThrows(
				NullPointerException.class,
				() -> predicate.test(null)
		);
	}
}