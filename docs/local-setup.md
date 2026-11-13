# Running TextLoop locally

This is a development setup with simulated sending. No SMS account or real phone
number is needed. The web UI and backend run together; there is no separate frontend build.

These commands use bash or zsh on macOS or Linux. Install Git,
[mise](https://mise.jdx.dev/getting-started.html), and
[Docker](https://docs.docker.com/get-started/get-docker/), and start Docker first.

## Get the project and Java

```sh
git clone https://github.com/hayleeekay/TextLoop.git
cd TextLoop
mise trust
mise install
```

`mise.toml` selects Java 25 for this project. The commands below use `mise exec`
so you do not need to change your global Java version. Maven is included through
the wrapper; Node and npm are not needed.

## Start PostgreSQL

In the same terminal, set the connection details:

```sh
export DB_USERNAME=textloop
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/textloop
```

Run this command, then type a local database password and press Enter. The password
will not be displayed or included in the command history:

```sh
IFS= read -r -s DB_PASSWORD
```

Then create the database container:

```sh
export DB_PASSWORD
POSTGRES_PASSWORD="$DB_PASSWORD" docker run --name textloop-db \
  --env POSTGRES_DB=textloop \
  --env POSTGRES_USER="$DB_USERNAME" \
  --env POSTGRES_PASSWORD \
  --publish 127.0.0.1:55432:5432 \
  --volume textloop-data:/var/lib/postgresql/data \
  --detach postgres:16-alpine
```

This creates a dedicated database on local port `55432`. The named volume
`textloop-data` keeps its data when the container stops.

Check that it is ready:

```sh
docker exec textloop-db pg_isready -U "$DB_USERNAME" -d textloop
```

If it is not accepting connections yet, wait a few seconds and check again.
An existing PostgreSQL database can also be used by setting `DB_USERNAME`,
`DB_PASSWORD`, and `SPRING_DATASOURCE_URL` to match it.

## Start the app

Keep the database variables in this terminal, then run:

```sh
SERVER_ADDRESS=127.0.0.1 mise exec -- ./mvnw spring-boot:run
```

Once startup finishes, open [http://127.0.0.1:8080/](http://127.0.0.1:8080/).
Spring creates the development tables on startup. The address keeps the app local
to this computer; it currently has no authentication.

## Try a reminder

1. Open **Messages** and type a reminder. The local app starts with the fictional
   number `+12035550100`. You can change it in **Settings**.
2. The composer starts with tomorrow at 9 AM. To try a reminder sooner, edit the
   date and time above your message, then tap the send arrow.
3. Open **Schedule** to edit the reminder before sending begins or withdraw it.
4. For a reminder left scheduled, wait until it is due. The server checks every
   minute, and the page updates while it is open.
5. Find it in **History**, which shows sent and withdrawn reminders for seven days.
   **Reuse** copies the text into Messages, where you can edit it and choose a new time.

“Sent (simulated)” means the fake sender accepted it. No text reaches a phone.
Replies do not snooze reminders yet. Times use the server's local timezone, so use
this preview on the same computer as the backend for now.

## Stop and come back later

Press **Ctrl+C** in the app terminal to stop the backend. Scheduled processing stops
too; overdue pending reminders are checked when the app runs again.

You can leave PostgreSQL running, or stop and restart it with:

```sh
docker stop textloop-db
docker start textloop-db
```

On later runs, start the existing container rather than running `docker run` again.
In a new terminal, set the three database variables again using the same password,
then run the app command. Changing the environment variable does not change an
existing database's password.

## Tests and common setup problems

```sh
mise exec -- ./mvnw verify
```

Tests use their own PostgreSQL containers through Testcontainers. Docker must be
running; the development database is not used by the tests.

- **Port already in use:** choose another host port in `--publish` and update
  `SPRING_DATASOURCE_URL` to match. For the app, set `SERVER_PORT` to another port
  before running it and open that port in the browser.
- **Database connection refused:** check that the container is running and
  `pg_isready` succeeds, and that the URL uses the published host port.
- **Password authentication failed:** use the password chosen when the database was
  first created, and check that the variables are set in the app's terminal.

For request examples and delivery-state details, see the [API guide](api-guide.md).
