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
    assert cs.set_password("bean", "$argon2i$x").status == "skipped"
    cs.mirror_signup("bean", "$argon2i$x", 1)  # logs, never raises
    cs.mirror_password("bean", "$argon2i$x")
