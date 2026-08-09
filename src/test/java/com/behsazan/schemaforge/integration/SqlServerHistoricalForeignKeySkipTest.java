package com.behsazan.schemaforge.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Regression coverage for HISTORICAL-mode SQL Server foreign-key validation skipping. */
class SqlServerHistoricalForeignKeySkipTest {

    @Test
    void matchesPostValidationToTheSkippedForeignKey() {
        String add = "ALTER TABLE TSTSHMA.JTMSCUSTOMERS WITH CHECK ADD CONSTRAINT "
                + "FK_JTMSCUSTOMERS_BRANCH FOREIGN KEY (BRANCH) "
                + "REFERENCES TSTSHMA.BRANCH(ID);";
        String check = "ALTER TABLE TSTSHMA.JTMSCUSTOMERS "
                + "CHECK CONSTRAINT FK_JTMSCUSTOMERS_BRANCH;";

        assertEquals(
                SqlServerDirectoryExecutionTest.foreignKeyConstraintKey(add),
                SqlServerDirectoryExecutionTest.checkedConstraintKey(check));
    }

    @Test
    void doesNotMatchAnUnrelatedCheckConstraint() {
        String add = "ALTER TABLE [TSTSHMA].[T] WITH CHECK ADD CONSTRAINT [FK_T_R] "
                + "FOREIGN KEY ([RID]) REFERENCES [TSTSHMA].[R]([ID]);";
        String check = "ALTER TABLE [TSTSHMA].[T] CHECK CONSTRAINT [CHK_T_AMOUNT];";

        assertNotEquals(
                SqlServerDirectoryExecutionTest.foreignKeyConstraintKey(add),
                SqlServerDirectoryExecutionTest.checkedConstraintKey(check));
    }
}
