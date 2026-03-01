1) “Cómo configurarlo” (en la práctica: reglas para guiar a Claude)

Instrucciones para decirle (copy/paste)
•	“No escribas 200 archivos de una. Primero proponé plan + estructura + 3 archivos base + 5 tests.”
•	“Todo lo que generes tiene que compilar y correr tests en local y en CI.”
•	“Preferí diseño simple: 3–5 módulos, nombres explícitos, sin patrones raros.”
•	“Cada función compleja: comentario corto ‘por qué funciona’.”
•	“Primero core logic + unit tests. Después API/CLI.”
•	“Nada de dependencias innecesarias.”
•	“Si proponés concurrency, justificá con un escenario real y agregá tests de race/consistencia.”
•	“Mantené logs mínimos y útiles; no inundes.”
•	“Documentá trade-offs: por qué elegiste X y no Y.”

Archivos que lo “encarrilan” automáticamente
•	README.md con “Definition of Done”
•	DESIGN.md (en la carpeta del problema)
•	CONTRIBUTING.md con reglas de estilo
•	.github/workflows/ci.yml (para que no se escape: si rompe CI, no avanza)
•	Makefile o scripts (./scripts/test.sh, ./scripts/lint.sh)

Definition of Done (mini):
•	make test / npm test / gradle test pasa
•	80%+ cobertura en core (no hace falta decir 80, pero sí “core bien cubierto”)
•	README con cómo correr
•	DESIGN con trade-offs

⸻

2) Qué encarar para un puesto TL Backend (opciones con “señal”)

La clave acá no es hacer algo gigante: es elegir un problema que te deje demostrar calidad técnica, criterio, tests, y trade-offs.

Opción A — Rate Limiter

Por qué garpa para TL: mezcla diseño + algoritmos + edge cases + concurrencia.
•	Implementás Token Bucket / Leaky Bucket / Fixed/Sliding Window
•	API simple: allow(key): bool + métricas
•	Tests: bursts, límites por tiempo, concurrencia, reloj controlado (clock injection)
•	Bonus: soporte in-memory + interfaz “pluggable” para Redis (sin implementarlo full)

Opción B — URL Shortener

Por qué sirve: es clásico y evaluable.
•	Generación de IDs (Base62, Snowflake-like, hash + colisiones)
•	Persistencia (in-memory + interfaz)
•	Tests: colisiones, idempotencia, expiración (si agregás TTL)
•	Bonus: diseño de “read heavy”, caching simple

Opción C — Log aggregation / Distributed logging

Por qué suma: es “backend infrastructure vibe”.
•	Ingesta, colas, batching, backpressure
•	Puede complicarse; ojo con overengineering
•	Si lo hacés, mantenelo minimal: core + tests de batching y límites

Opción D — Simple Search Autocomplete

Por qué sirve: estructura de datos (Trie), performance, tests.
•	Buen balance entre simple y demostrable

Mi recomendación TL (fuerte): Rate Limiter.
Es el que más te deja lucirte sin inflarte en scope.

⸻

3) Contexto de los libros (sin depender de links)

“System Design Interview – An Insider’s Guide (Vol. 1)” — Alex Xu

Es un libro orientado a entrevistas de system design.
Lo típico: te presenta problemas comunes (URL shortener, rate limiter, news feed, etc.) y te guía a:
•	Requisitos (funcionales / no funcionales)
•	Estimaciones (QPS, storage)
•	Diseño de alto nivel (componentes)
•	Deep dives (caching, sharding, consistency, etc.)

En tu challenge, lo importante es: elegís uno de esos problemas y lo implementás como prototipo, no solo el diagrama.

“A Philosophy of Software Design” — John Ousterhout

Es un libro de diseño de software enfocado en reducir complejidad. Ideas centrales:
•	La complejidad mata maintainability
•	Interfaces claras > implementaciones “ingeniosas”
•	Encapsulación real: esconder complejidad detrás de módulos simples
•	Evitar “shallow modules” (mucho boilerplate para poco valor)
•	Diseñar APIs para que el usuario del módulo piense menos

Para el challenge, lo podés usar como “marco mental”:
•	pocos módulos, cada uno con responsabilidad clara
•	API mínima y obvia
•	tests que describen comportamiento
