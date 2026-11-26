import central_sync as cs

NO_WORDS = frozenset()


def test_canonicalize_collapses_spaces_and_drops_symbols():
    assert cs.canonicalize("  Bean   Boy ") == "Bean Boy"
    assert cs.canonicalize("Rs@Player") == "RsPlayer"
    assert cs.clean_login_name(" Wise  Old Bean ") == "wise old bean"


def test_collision_key_ignores_spaces_and_case():
    assert cs.collision_key("Bean Boy") == cs.collision_key("beanboy") == "beanboy"


def test_accepts_central_login_names():
    for name in ("wiseoldbean", "umpix", "bean boy", "Zezima", "a1", "admin"):
        assert cs.name_problem(name, NO_WORDS) is None, name


def test_refuses_what_central_refuses_at_login():
    assert "symbols" in cs.name_problem("bean_boy", NO_WORDS)
    assert "symbols" in cs.name_problem("bean-boy", NO_WORDS)
    assert "at most 12" in cs.name_problem("thirteenchars", NO_WORDS)
    assert "at least 2" in cs.name_problem("x", NO_WORDS)
    assert '"mod"' in cs.name_problem("Modbean", NO_WORDS)
    assert "staff" in cs.name_problem("jagexbean", NO_WORDS)
    assert "staff" in cs.name_problem("beansupport", NO_WORDS)
    assert cs.name_problem("", NO_WORDS) == "Enter a name."
    assert "a-z" in cs.name_problem("beán", NO_WORDS)


def test_blocked_words_match_with_and_without_spaces():
    words = cs._parse_words("# comment\nbad word\nnope  # trailing\n")
    assert words == frozenset({"bad word", "nope"})
    assert cs.find_bad_word("badword", words) == "bad word"
    assert cs.find_bad_word("xnopex", words) == "nope"
    assert "blocked" in cs.name_problem("my bad word", words)
    assert cs.name_problem("fine name", words) is None


def test_rights_mapping():
    assert cs.RIGHTS_FOR_MODLEVEL["owner"] == "ADMINISTRATOR"
    assert cs.RIGHTS_FOR_MODLEVEL["admin"] == "ADMINISTRATOR"
    assert cs.RIGHTS_FOR_MODLEVEL["moderator"] == "MOD"
    assert cs.RIGHTS_FOR_MODLEVEL.get("") is None


def test_unconfigured_host_skips_without_error(tmp_path, monkeypatch):
    monkeypatch.setattr(cs, "SECRETS_FILE", tmp_path / "missing.env")
    assert cs.upsert_account("bean", "$argon2i$x").status == "skipped"
    assert cs.set_password("bean", "$argon2i$x", 1).status == "skipped"
    cs.mirror_signup("bean", "$argon2i$x", 1)  # logs, never raises
    cs.mirror_password("bean", "$argon2i$x", 1)


class _FakeCentral:
    """Just enough of a psycopg connection for upsert_account / set_password."""

    def __init__(self, rows):
        self.rows = rows  # lower(name) -> [id, discord_id, hash]
        self.result = None
        self.rowcount = 0

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def cursor(self):
        return self

    def close(self):
        pass

    def execute(self, sql, params):
        if sql.startswith("SELECT id, discord_id"):
            row = self.rows.get(params[0].lower())
            self.result = (row[0], row[1]) if row else None
        elif sql.startswith("SELECT account_name"):
            self.result = None
        elif sql.startswith("UPDATE accounts SET password_hash = %s, updated_at"):
            for row in self.rows.values():
                if row[0] == params[1]:
                    row[2] = params[0]
        elif sql.startswith("INSERT INTO accounts"):
            self.rows[params[0].lower()] = [len(self.rows) + 1, params[3], params[1]]
            self.result = (len(self.rows),)

    def fetchone(self):
        return self.result


def _with_central(monkeypatch, rows):
    fake = _FakeCentral(rows)
    monkeypatch.setattr(cs, "connect", lambda: fake)
    return fake


def test_reset_only_touches_the_same_discord_users_account(monkeypatch):
    fake = _with_central(monkeypatch, {"bean": [1, "111", "old"]})
    assert cs.set_password("bean", "new", 222).status == "conflict"
    assert fake.rows["bean"][2] == "old"
    assert cs.set_password("bean", "new", 111).status == "updated"
    assert fake.rows["bean"][2] == "new"


def test_reset_of_unlinked_central_account_is_refused(monkeypatch):
    fake = _with_central(monkeypatch, {"bean": [1, None, "old"]})
    assert cs.set_password("bean", "new", 111).status == "conflict"
    assert fake.rows["bean"][2] == "old"


def test_signup_never_claims_someone_elses_central_name(monkeypatch):
    _with_central(monkeypatch, {"bean": [1, "111", "old"]})
    assert cs.upsert_account("bean", "h", 222).status == "conflict"
    assert cs.upsert_account("bean", "h", 111).status == "exists"
    assert cs.upsert_account("fresh", "h", 333).status == "created"


def test_blocked_word_list_is_off_by_default(monkeypatch):
    monkeypatch.setattr(cs, "BAD_WORDS_URL", "")
    assert cs.bad_words() == frozenset()
    for name in ("bean smith", "thomas", "jason", "sussex"):
        assert cs.name_problem(name) is None, name
