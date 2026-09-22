package com.snippetveil.plugin.query

import com.snippetveil.core.SymbolRole
import com.snippetveil.plugin.symbols

/**
 * **HQL rides the same trigger as JPQL** — one fixture, on Hibernate's own `@NamedQuery`, whose query
 * the IDE injects as HQL rather than JPQL. It binds, so it decomposes, onto the same placeholders the
 * entity's Java symbols take; and a query that does not bind is one redacted literal.
 */
internal class HqlQueryTest : HibernateSnippetTestCase() {

    fun `test an HQL query decomposes by binding, onto the entity's placeholders`() {
        assertTheQueryHarnessHolds()
        val query = "from Customer c where c.merchantRef = ?1"
        val plan = planFor(
            "Customer.java",
            """
            package com.acme.billing;

            import jakarta.persistence.*;

            <selection>@Entity
            @org.hibernate.annotations.NamedQuery(name = "byRef", query = "$query")
            public class Customer {
                @Id Long id;
                String merchantRef;
            }</selection>
            """.trimIndent(),
        )
        assertInjected(myFixture.file, "\"$query\"", HQL)

        assertEquals(
            "@Entity\n" +
                "@org.hibernate.annotations.NamedQuery(name = \"str1\", query = \"from Type2 local3 where local3.field4 = ?1\")\n" +
                "public class Type2 {\n" +
                "    @Id Long field5;\n" +
                "    String field4;\n" +
                "}",
            resultOf(plan).text,
        )
    }

    fun `test an HQL query that does not bind is one redacted literal`() {
        assertTheQueryHarnessHolds()
        val query = "from Customer c where c.nothingCalledThis = ?1"
        val plan = planFor(
            "Customer.java",
            """
            package com.acme.billing;

            import jakarta.persistence.*;

            <selection>@Entity
            @org.hibernate.annotations.NamedQuery(name = "byRef", query = "$query")
            public class Customer {
                @Id Long id;
                String merchantRef;
            }</selection>
            """.trimIndent(),
        )
        assertInjected(myFixture.file, "\"$query\"", HQL)

        assertEquals("the query was not one literal", listOf("\"byRef\"", "\"$query\""), plan.literalTexts())
    }
}

/**
 * **Spring Data QL rides the same trigger** — one fixture, on a repository's `@Query`, which Spring Data
 * injects in a language of its own. The repository names the entity and its property from another file,
 * and each still takes the key the Java symbol has.
 */
internal class SpringDataQueryTest : QuerySnippetTestCase() {

    fun `test a Spring Data query decomposes by binding, onto the entity's keys`() {
        assertTheQueryHarnessHolds()
        addProjectFile(
            "com/acme/billing/Customer.java",
            """
            package com.acme.billing;

            import jakarta.persistence.*;

            @Entity
            public class Customer {
                @Id Long id;
                String merchantRef;
            }
            """.trimIndent(),
        )
        val query = "SELECT c FROM Customer c WHERE c.merchantRef = :ref"
        val plan = planFor(
            "CustomerRepository.java",
            """
            package com.acme.billing;

            import org.springframework.data.jpa.repository.JpaRepository;
            import org.springframework.data.jpa.repository.Query;
            import org.springframework.data.repository.query.Param;

            public interface CustomerRepository extends JpaRepository<Customer, Long> {
                <selection>@Query("$query")
                Customer findByRef(@Param("ref") String ref);</selection>
            }
            """.trimIndent(),
        )
        assertInjected(myFixture.file, "\"$query\"", SPRING_DATA_QL)

        val customer = plan.symbols().filter { it.text == "Customer" }
        assertEquals("the query and the return type should both name Customer", 2, customer.size)
        assertEquals("the entity name in the query is not the class", 1, customer.map { it.symbol.key }.distinct().size)
        assertEquals(SymbolRole.FIELD, plan.symbols().single { it.text == "merchantRef" }.symbol.role)

        val parameter = plan.symbols().filter { it.text == "ref" }
        val bind = parameter.first { plan.text[it.start - 1] == ':' }
        assertEquals("the bind parameter is not a local", SymbolRole.LOCAL, bind.symbol.role)
        assertTrue(
            "the bind parameter took the method parameter's key, which asserts a binding the query does not make",
            parameter.filter { it !== bind && it.symbol.role == SymbolRole.PARAMETER }.none { it.symbol.key == bind.symbol.key },
        )

        val result = resultOf(plan)
        assertTrue("the query did not decompose: ${result.text}", Regex("""@Query\("SELECT (local\d+) FROM Type\d+ \1 WHERE \1\.field\d+ = :local\d+"\)""").containsMatchIn(result.text))
    }
}

private const val HQL = "HQL"

private const val SPRING_DATA_QL = "SpringDataQL"
