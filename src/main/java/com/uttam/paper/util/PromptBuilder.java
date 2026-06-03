package com.uttam.paper.util;

import com.uttam.paper.model.Category;
import com.uttam.paper.model.NotificationData;
import com.uttam.paper.model.PaperType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Builds a role- and type-aware prompt for the Gemini API.
 *
 * <p>The 4 sets generated per run are:
 * <ul>
 *   <li>Set 1 — Junior  / Non-Technical</li>
 *   <li>Set 2 — Junior  / Technical</li>
 *   <li>Set 3 — Senior  / Non-Technical</li>
 *   <li>Set 4 — Senior  / Technical</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptBuilder {

    private static final DateTimeFormatter DATE_FMT       = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int               QUESTIONS_PER_SET = 100;
    private static final int               OPTIONS_PER_Q     = 4;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds a prompt for a single set identified by role and paper type.
     *
     * @param data      parsed notification data
     * @param setNumber set number (1–4)
     * @param role      JUNIOR or SENIOR
     * @param type      TECHNICAL or NON_TECHNICAL
     * @return ready-to-send prompt string
     */
    public String buildForSet(NotificationData data, int setNumber, Category role, PaperType type) {
        String date = LocalDate.now().format(DATE_FMT);
        String seed = UUID.randomUUID().toString();

        log.info("Building prompt | date={} | seed={} | set={} | role={} | type={} | title={}",
                date, seed, setNumber, role, type, data.getTitle());

        return new StringBuilder()
                .append(uniquenessHeader(date, seed, setNumber, role, type))
                .append(bilingualInstruction())
                .append(examContext(data))
                .append(examFormatInstruction())
                .append(paperTypeInstruction(role, type, data))
                .append(keywordInstruction(data.getKeywords(), type))
                .append(outputFormat(setNumber))
                .append(uniquenessFooter())
                .toString();
    }

    // -------------------------------------------------------------------------
    // Prompt sections
    // -------------------------------------------------------------------------

    private String uniquenessHeader(String date, String seed, int setNumber,
                                    Category role, PaperType type) {
        return """
                === SESSION METADATA (do not include in output) ===
                Generation Date : %s
                Unique Seed     : %s
                Set Number      : %d
                Role Level      : %s
                Paper Type      : %s
                =====================================================

                """.formatted(date, seed, setNumber,
                role.name(), type.name().replace('_', ' '));
    }

    private String bilingualInstruction() {
        return """
                BILINGUAL FORMAT — MANDATORY — Follow this EXACTLY for every single question:

                Q<number>.
                [EN] <Complete question in English>
                (a) <Option A in English>  (b) <Option B in English>  (c) <Option C in English>  (d) <Option D in English>
                [HIN] <Same question in Hindi>
                (a) <Option A in Hindi>  (b) <Option B in Hindi>  (c) <Option C in Hindi>  (d) <Option D in Hindi>
                Correct Answer: (<letter>)

                CRITICAL RULES:
                - Q<number>. must be on its OWN line. Do NOT write "Q1. [EN]..." on the same line.
                - [EN] must ALWAYS appear on a NEW line by itself followed by the English text.
                - [HIN] must ALWAYS appear on a NEW line by itself followed by the Hindi text.
                - Every question MUST have BOTH [EN] and [HIN] blocks. Never skip either language.

                """;
    }

    private String examContext(NotificationData data) {
        return """
                EXAM CONTEXT:
                Notification Title : %s
                Syllabus           : %s
                Exam Pattern       : %s

                """.formatted(
                nullSafe(data.getTitle()),
                nullSafe(data.getSyllabus()),
                nullSafe(data.getExamPattern()));
    }

    private String examFormatInstruction() {
        return """
                EXAM FORMAT (RSSB Standard):
                - Total questions per paper : %d MCQs
                - Options per question      : %d (a, b, c, d)
                - Each question carries     : 1 mark
                - Negative marking          : 1/3 mark deducted for wrong answer
                - Time allowed             : 2 hours

                """.formatted(QUESTIONS_PER_SET, OPTIONS_PER_Q);
    }

    /**
     * Core instruction section — varies by role (JUNIOR/SENIOR) and type (TECHNICAL/NON_TECHNICAL).
     */
    private String paperTypeInstruction(Category role, PaperType type, NotificationData data) {
        String roleDesc   = roleDescription(role);
        String topicBlock = topicBlock(role, type, data);

        return """
                PAPER SPECIFICATION:
                Role Level : %s
                Paper Type : %s

                %s
                %s

                SET INSTRUCTION:
                - Generate exactly 1 complete question paper for this role and type.
                - The paper must contain exactly %d unique questions numbered Q1 to Q%d.
                - ALL questions must be appropriate for the %s role at %s level.
                - Questions must NOT overlap with any other set in this run.

                """.formatted(
                role.name(),
                type.name().replace('_', ' '),
                roleDesc,
                topicBlock,
                QUESTIONS_PER_SET, QUESTIONS_PER_SET,
                type.name().replace('_', ' ').toLowerCase(),
                role.name().toLowerCase());
    }

    private String roleDescription(Category role) {
        return switch (role) {
            case JUNIOR -> """
                    JUNIOR TECHNICAL PAPER — DIFFICULTY & STYLE SPECIFICATION:
                    Model: Rajasthan Computer Instructor 2022 Technical Paper (Junior). Match or slightly exceed that difficulty.

                    DIFFICULTY DISTRIBUTION (strictly follow):
                    - Easy (15 questions): Direct recall of well-known facts/formulae — e.g., "What does DBMS stand for?" level. These must still require some thought, not be trivially obvious.
                    - Medium (55 questions): Require understanding, NOT just recall. Test ability to distinguish between similar concepts, predict outputs, identify errors, or apply rules. Examples: spot invalid SQL, predict stack output, identify which layer a protocol belongs to, convert binary to hex.
                    - Difficult (30 questions): Require multi-step reasoning, code/query trace, algorithm analysis, or scenario-based decision. Examples: trace recursive function output, evaluate postfix expression, identify correct normalization form given a schema, debug a logic circuit, compute subnet mask for a given requirement.

                    QUESTION DESIGN RULES:
                    - ZERO trivial definition-based questions. "Which of the following is NOT a feature of X?" is acceptable; "Define X" style is NOT.
                    - At least 40% of questions must require reasoning (not lookup).
                    - At least 20% must involve interpreting code snippets, SQL queries, truth tables, or tree/graph diagrams described in text.
                    - ALL four options (a, b, c, d) must be technically plausible — never include obviously wrong distractors.
                    - Use concise, unambiguous English phrasing. Avoid ambiguous or trick wording.
                    - Questions about outputs must provide a clear, executable snippet or expression.""";
            case SENIOR -> """
                    SENIOR TECHNICAL PAPER — DIFFICULTY & STYLE SPECIFICATION:
                    Model: Rajasthan Computer Instructor 2022 Technical Paper (Senior). Match or slightly exceed that difficulty.

                    DIFFICULTY DISTRIBUTION (strictly follow):
                    - Easy (10 questions): Conceptual recall that still requires understanding — e.g., distinguishing TCP vs UDP by property, not by name.
                    - Medium (45 questions): Deep understanding — scenario-based reasoning, multi-concept application, normalization decisions, scheduling algorithm selection, complexity comparison, protocol selection for a described network need.
                    - Difficult (45 questions): Advanced analytical, multi-step problems — recursive algorithm trace with stack state, complex SQL (nested subqueries, correlated queries, GROUP BY with HAVING), deadlock detection in a resource-allocation scenario, IPv4/IPv6 subnetting, cache coherence, cryptographic scheme selection, UML diagram interpretation.

                    QUESTION DESIGN RULES:
                    - NO memory-based definitions. Every question must test understanding or application.
                    - At least 40% of questions must require multi-step reasoning or analysis.
                    - At least 25% must involve a code snippet, SQL query, network diagram description, or system scenario that the candidate must interpret.
                    - Distractors must be technically sound — experienced candidates should need to think carefully to eliminate them.
                    - For OS questions: prefer process-state traces, scheduling Gantt charts, or deadlock scenarios over simple definitions.
                    - For DBMS questions: prefer schema-based normalization, complex joins, or query correction over isolated keyword recall.
                    - For Networks questions: prefer routing table analysis, subnet calculations, or protocol-behaviour scenarios.""";
        };
    }

    private String topicBlock(Category role, PaperType type, NotificationData data) {
        return switch (type) {
            case NON_TECHNICAL -> """
                    NON-TECHNICAL TOPICS (General Ability & Knowledge):
                    Cover a broad mix of the following topics:
                    1. History, Art & Culture of Rajasthan
                    2. Geography of Rajasthan (rivers, lakes, districts, climate)
                    3. General Science (Physics, Chemistry, Biology — Class X level)
                    4. Current Affairs (Rajasthan & National — last 1 year)
                    5. Indian Constitution & Polity
                    6. Economy of Rajasthan & India
                    7. Logical Reasoning & Analytical Ability
                    8. Data Interpretation (charts, tables, graphs)
                    9. Basic Numeracy & Number Systems (Class X level)
                    10. Decision Making & Problem Solving
                    Ensure all 100 questions are purely general/non-technical in nature.""";
            case TECHNICAL -> (role == Category.JUNIOR
                    ? juniorTechnicalTopicBlock(data)
                    : seniorTechnicalTopicBlock(data));
        };
    }

    private String juniorTechnicalTopicBlock(NotificationData data) {
        return """
                JUNIOR TECHNICAL TOPICS — Computer Instructor (Rajasthan) Pattern:
                Post context: %s
                Distribute 100 questions EXACTLY as shown (approximate counts):

                1. Computer Fundamentals (10 questions)
                   - Types of computers, components, I/O devices, memory hierarchy, cache, ROM vs RAM types
                   - Number systems: binary/octal/decimal/hex conversions, BCD, 1s/2s complement arithmetic
                   - Boolean algebra, logic gate circuits, half-adder, full-adder truth tables
                   Sample question style: "Evaluate: (1011)₂ + (0110)₂ = ?" or "Which gate produces HIGH only when all inputs are LOW?"

                2. Operating System (10 questions)
                   - Process states, PCB, context switching, zombie/orphan processes
                   - CPU scheduling algorithms (FCFS, SJF, Round Robin, Priority) — compute average waiting time
                   - Memory management: paging, segmentation, page faults, thrashing
                   - Deadlock: conditions, prevention, Banker's algorithm concept
                   - File systems: FAT, NTFS, inodes
                   Sample question style: "Given arrival times [0,2,4] and burst times [6,4,2], compute average waiting time for SJF (non-preemptive)."

                3. MS Office & Productivity Tools (10 questions)
                   - Excel: function syntax (VLOOKUP, IF, COUNTIF, SUMIF), cell referencing ($A$1 vs A1), pivot tables
                   - Word: mail merge steps, styles, section breaks, track changes
                   - PowerPoint: slide master, transitions vs animations, presenter view
                   - Shortcut keys, file formats (.docx vs .doc, .xlsx vs .xls, .csv)
                   Sample question style: "=IF(AND(A1>50, B1<100), \"Pass\", \"Fail\") — what does this return when A1=60, B1=80?"

                4. Internet & Networking (15 questions)
                   - OSI model layers: correct protocol-to-layer mapping (HTTP→Application, TCP→Transport, IP→Network, ARP→Data Link)
                   - TCP/IP model vs OSI model: layer correspondence
                   - IP addressing: class identification (Class A/B/C ranges), subnet mask, CIDR notation, broadcast address calculation
                   - DNS, DHCP, FTP, SMTP, POP3, IMAP — purpose and default ports
                   - MAC address format, ARP/RARP, NAT, PAT
                   - Network topologies: star, bus, ring, mesh — pros/cons
                   Sample question style: "A host has IP 192.168.10.50/27. What is the broadcast address of this subnet?"

                5. Database Management System (15 questions)
                   - Keys: primary, candidate, super, foreign, composite — differences and examples
                   - Normalization: 1NF, 2NF, 3NF, BCNF — identify violations and correct form given a schema
                   - SQL: SELECT with WHERE, GROUP BY, HAVING, ORDER BY; JOINS (INNER, LEFT, RIGHT, FULL); subqueries
                   - SQL functions: COUNT, SUM, AVG, MAX, MIN, GROUP_CONCAT; NULL handling (IS NULL, COALESCE)
                   - Transactions: ACID properties, COMMIT, ROLLBACK, SAVEPOINT
                   - ER model: entity, attribute types (multivalued, derived, composite), relationship cardinality
                   Sample question style: "Which SQL query correctly finds departments with more than 3 employees?" (give 4 SQL options)

                6. Programming Fundamentals (10 questions)
                   - Arrays, functions, pointers (C), parameter passing (call by value vs reference)
                   - Control flow: nested loops output prediction, recursion trace
                   - OOP concepts: class, object, inheritance, polymorphism, encapsulation — identify which principle applies
                   - Python basics: list comprehension, string methods, dictionary operations
                   Sample question style: "What is the output of: int a=5; printf(\"%d %d\", a++, ++a);"

                7. Data Structures (10 questions)
                   - Stack: push/pop sequence, postfix/prefix expression evaluation step-by-step
                   - Queue: FIFO, circular queue, priority queue operations
                   - Binary tree: inorder/preorder/postorder traversal output for a given tree
                   - Searching: binary search steps, hash collision resolution
                   - Sorting: compare bubble/selection/insertion/merge/quick — time complexities, best/worst case
                   Sample question style: "Evaluate postfix: 6 2 3 + - 3 8 2 / + * 2 $ 3 +"

                8. Software Engineering (5 questions)
                   - SDLC models: Waterfall, Spiral, Agile, RAD — which model suits which scenario
                   - DFD levels (context, level-0, level-1), process vs data store vs external entity
                   - Testing: unit, integration, system, acceptance; white-box vs black-box; boundary value analysis
                   Sample question style: "Which SDLC model is MOST suitable when client requirements change frequently?"

                9. Cyber Security (5 questions)
                   - Types of attacks: phishing, man-in-the-middle, SQL injection, XSS, brute force, DoS vs DDoS
                   - Cryptography: symmetric (AES, DES) vs asymmetric (RSA), public/private keys, digital signatures, certificates
                   - Hashing: MD5, SHA-1, SHA-256 — purpose (NOT encryption), collision
                   - Firewall types: packet-filter, stateful inspection, proxy
                   Sample question style: "Which of the following correctly describes a Man-in-the-Middle attack?" (give 4 scenario options)

                10. Digital Electronics & Number Systems (10 questions)
                    - Combinational circuits: multiplexer, demultiplexer, encoder, decoder
                    - Sequential circuits: flip-flops (SR, JK, D, T) — state table, characteristic equation
                    - Karnaugh map (K-map) simplification for 2 or 3 variables
                    - Number system arithmetic: addition/subtraction in binary, hex multiplication
                    Sample question style: "Simplify the Boolean expression AB + AB' + A'B using K-map."

                CRITICAL: Generate EXACTLY 100 questions following the above distribution.
                Do NOT generate simple recall questions like "What does CPU stand for?" or "Who invented the internet?"
                EVERY question must require the candidate to think, compute, or analyse."""
                .formatted(nullSafe(data.getTitle()));
    }

    private String seniorTechnicalTopicBlock(NotificationData data) {
        return """
                SENIOR TECHNICAL TOPICS — Computer Instructor (Rajasthan) Pattern:
                Post context: %s
                Distribute 100 questions EXACTLY as shown (approximate counts):

                1. Computer Organization & Architecture (10 questions)
                   - Instruction formats (R, I, J type), addressing modes (immediate, direct, indirect, indexed)
                   - Pipelining: stages, hazards (data, control, structural), stall resolution, speedup calculation
                   - Cache memory: direct-mapped, set-associative, fully-associative; LRU/FIFO replacement
                   - ALU operations, booth's algorithm concept, IEEE 754 floating-point representation
                   Sample question style: "A 4-stage pipeline has stage delays 10, 8, 12, 9 ns. What is the throughput for 100 instructions?"

                2. Operating System (10 questions)
                   - Advanced scheduling: multilevel feedback queue, real-time scheduling (EDF, RMS)
                   - Deadlock: resource-allocation graph analysis, Banker's algorithm — safe/unsafe state detection
                   - Virtual memory: page replacement (LRU, FIFO, Optimal) — compute page-fault rate for a given reference string
                   - IPC: semaphores, monitors, message passing — identify race condition in given pseudo-code
                   - Linux commands and system calls: fork(), exec(), wait()
                   Sample question style: "For page reference string 7,0,1,2,0,3,0,4,2,3,0,3,2 with 3 frames, compute page faults using LRU."

                3. Database Management System (15 questions)
                   - Advanced normalization: BCNF, 4NF, 5NF — decompose a given relation and verify lossless join / dependency preservation
                   - Relational algebra: SELECT, PROJECT, JOIN, INTERSECT, UNION — convert given SQL to relational algebra
                   - Complex SQL: correlated subqueries, EXISTS/NOT EXISTS, UNION vs UNION ALL, window functions (ROW_NUMBER, RANK, DENSE_RANK)
                   - Query optimisation concepts: indexes (B+Tree, Hash), query execution plan
                   - Concurrency control: 2PL (strict, conservative), timestamp ordering, conflict serializability — check if a schedule is conflict-serializable
                   Sample question style: "Given schedule S: T1: r(A), T2: r(A), T1: w(A), T2: w(A) — is S conflict-serializable? Justify."

                4. Data Structures & Algorithms (15 questions)
                   - Tree structures: AVL tree rotations (LL, RR, LR, RL) — insert a sequence and identify resulting tree height
                   - B-tree and B+-tree: insertion, deletion, minimum degree concept
                   - Graph algorithms: Dijkstra's shortest path trace, Prim's/Kruskal's MST, BFS/DFS order for a given graph
                   - Hashing: open addressing (linear/quadratic probing), chaining — compute load factor, worst-case lookup
                   - Time & space complexity: identify recurrence relation, solve by Master Theorem
                   - Dynamic programming: 0/1 knapsack trace, LCS computation
                   Sample question style: "Apply Dijkstra's algorithm on the given weighted graph (described in the question). What is the shortest path cost from A to D?"

                5. Computer Networks (15 questions)
                   - IPv4 subnetting: CIDR, VLSM — compute network address, broadcast, usable range, number of subnets
                   - IPv6: address notation, types (unicast, multicast, anycast), transition mechanisms (6to4, tunnelling)
                   - Routing protocols: distance-vector (RIP) vs link-state (OSPF) — convergence, count-to-infinity, split horizon
                   - TCP: 3-way handshake, congestion control (slow start, congestion avoidance, fast retransmit), flow control (sliding window)
                   - Network security: VPN, SSL/TLS handshake, IPSec (AH vs ESP), firewall vs IDS vs IPS
                   - Wireless: 802.11 standards (a/b/g/n/ac), CSMA/CA vs CSMA/CD
                   Sample question style: "A network has address 172.16.0.0/12 and needs to be divided into 8 equal subnets. What is the new prefix length?"

                6. Programming & OOP (10 questions)
                   - Inheritance: method overriding vs overloading, virtual functions, vtable concept
                   - Abstract classes vs interfaces — when to use which; multiple inheritance issues (diamond problem)
                   - Exception handling: try-catch-finally execution order for given code snippets
                   - Java/C++ specific: garbage collection, constructors/destructors, operator overloading rules
                   - Design patterns: identify which pattern (Singleton, Factory, Observer, Decorator) is being described in a scenario
                   Sample question style: "Given Java code with multiple catch blocks and a finally block, predict the output and state which catch is reached."

                7. Software Engineering (10 questions)
                   - SDLC comparison: Agile vs Waterfall vs DevOps — choose best for a described project scenario
                   - UML diagrams: identify diagram type from description; read a sequence/class/use-case diagram
                   - Testing: cyclomatic complexity calculation (V(G) = E - N + 2P), equivalence partitioning, boundary value analysis
                   - Software metrics: cohesion vs coupling types; lines of code vs function points
                   - Risk management: identify risk type (schedule, technical, business) in a given scenario
                   Sample question style: "A program's control flow graph has 8 edges, 6 nodes, and 1 connected component. What is its cyclomatic complexity?"

                8. Cyber Security (5 questions)
                   - Public-key infrastructure (PKI): certificate authority, chain of trust, revocation (CRL, OCSP)
                   - Attack classification: social engineering, zero-day exploit, APT, ransomware — identify from scenario
                   - Encryption modes: ECB vs CBC vs CTR — which is insecure and why
                   - Digital forensics concepts: chain of custody, volatile vs non-volatile evidence
                   Sample question style: "Which encryption mode is vulnerable to pattern analysis when the same plaintext block always produces the same ciphertext?"

                9. Web Technologies (5 questions)
                   - HTTP vs HTTPS: status codes (200, 301, 302, 400, 401, 403, 404, 500), methods (GET/POST/PUT/DELETE/PATCH)
                   - REST API: statelessness, idempotency — which HTTP methods are idempotent
                   - HTML5 semantic tags, CSS box model, JavaScript event loop (synchronous vs asynchronous, Promise, async/await)
                   - Web security: CORS, CSRF, XSS prevention, Content Security Policy
                   Sample question style: "Which HTTP methods are idempotent? Choose the BEST answer from the options."

                10. Cloud Computing & Emerging Technologies (5 questions)
                    - Service models: IaaS vs PaaS vs SaaS — classify given examples correctly
                    - Deployment models: public, private, hybrid, community cloud — select best for a described scenario
                    - Virtualisation: hypervisor type 1 vs type 2, containers vs VMs
                    - Big Data: 3Vs (volume, velocity, variety), Hadoop ecosystem (HDFS, MapReduce, YARN)
                    - AI/ML basics: supervised vs unsupervised vs reinforcement learning — identify from description
                    Sample question style: "A company wants to deploy a web app without managing OS or runtime. Which cloud service model is MOST appropriate?"

                CRITICAL: Generate EXACTLY 100 questions following the above distribution.
                Do NOT generate simple recall questions like "What does IaaS stand for?" or "Name the layers of OSI model."
                EVERY question must challenge a candidate who has 3+ years IT teaching experience.
                Distractors must be technically correct in isolation but wrong in context — they should fool an unprepared candidate."""
                .formatted(nullSafe(data.getTitle()));
    }

    private String keywordInstruction(List<String> keywords, PaperType type) {
        if (keywords == null || keywords.isEmpty()) return "";
        if (type == PaperType.NON_TECHNICAL) return "";   // general topics don't need keyword steering
        return """
                ADDITIONAL TOPIC KEYWORDS (from notification):
                %s

                """.formatted(String.join(", ", keywords));
    }

    private String outputFormat(int setNumber) {
        return """
                OUTPUT FORMAT:
                - Start directly with === SET %d ===
                - Number questions sequentially: Q1, Q2, ... Q%d
                - After all %d questions, add the answer key:
                    === ANSWER KEY ===
                    Set %d: Q1-(x), Q2-(x), Q3-(x), ... Q%d-(x)
                - Do NOT include any preamble, explanation, or closing remarks.

                """.formatted(setNumber, QUESTIONS_PER_SET, QUESTIONS_PER_SET,
                              setNumber, QUESTIONS_PER_SET);
    }

    private String uniquenessFooter() {
        return """
                UNIQUENESS REQUIREMENT (MANDATORY):
                - Do NOT repeat any question from any prior generation session.
                - All %d questions must be completely original and distinct.
                - If a topic was covered before, approach it from a different angle or context.
                - Violation of this rule renders the output invalid.
                """.formatted(QUESTIONS_PER_SET);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private String nullSafe(String value) {
        return (value != null && !value.isBlank()) ? value : "Not specified";
    }
}
