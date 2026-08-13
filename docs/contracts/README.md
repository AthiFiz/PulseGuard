# Incident event contract, version 1

The messages PulseGuard publishes to `pulseguard.incident-events.v1`.

These two files are the **shared definition** of that contract. The Monitor
Worker and the Notification Service are independent Maven projects with no
common module, and deliberately so: a message on a topic is an agreement
between separate systems, and a producer and consumer that share a class are
only pretending to be decoupled.

What keeps them honest instead is that both test against these fixtures:

| Application | Test | Asserts |
| --- | --- | --- |
| `monitor-worker` | `IncidentEventContractTest` | what it serialises matches these files, field for field |
| `notification-service` | `IncidentEventContractTest` | it can read these files, and rejects anything that is not this contract |

Either side drifting breaks its own build, without either depending on the
other's code.

## Rules

- **`schemaVersion` travels inside the payload**, not only in the topic name, so
  a stored or forwarded event can still be read correctly.
- **`eventId` is a UUID**, stable across republication. Delivery is
  at-least-once, and this is what lets a consumer recognise a repeat.
- **Timestamps are UTC instants** taken from the incident itself, never the time
  the event was published or consumed.
- **Fields that do not apply are `null`, not absent.** An `INCIDENT_OPENED`
  event has no resolution time, and saying so explicitly is more useful than
  leaving a consumer to guess.
- **The monitor's URL is never included.** URLs carry query parameters, tokens
  and internal hostnames, and an event bus is where data spreads. Neither are
  credentials, headers or response bodies.

## Changing the contract

A backwards-compatible addition (a new nullable field) can be made in place.

Anything incompatible — removing a field, changing a type, changing what a field
means — gets a **new topic** (`pulseguard.incident-events.v2`) and a new fixture
alongside these, so both versions can run while consumers migrate. The
Notification Service rejects a `schemaVersion` it does not know rather than
guessing.
