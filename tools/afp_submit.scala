/* Author: Fabian Huch, TU Muenchen

AFP submission system backend.
 */
package afp


import isabelle.*
import isabelle.HTML.*

import afp.Web_App.{ACTION, FILE, Params}
import afp.Web_App.Params.{List_Key, Nest_Key, empty}
import afp.Web_App.More_HTML.*
import afp.Metadata.{Affiliation, Author, Authors, DOI, Email, Entry, Entries, Formatted, Homepage, License, Licenses, Orcid, Reference, Release, Releases, Topic, Topics, Unaffiliated}

import java.text.Normalizer
import java.time.LocalDate


object AFP_Submit {
  /* (optional) values with errors */

  object Val_Opt {
    def ok[A](value: A): Val_Opt[A] = Val_Opt(Some(value), None)
    def user_err[A](msg: String): Val_Opt[A] = Val_Opt(None, Some(msg))
    def error[A]: Val_Opt[A] = Val_Opt(None, None)
  }

  case class Val_Opt[A] private(opt: Option[A], err: Option[String]) {
    def is_empty: Boolean = opt.isEmpty
  }

  object Val {
    def ok[A](value: A): Val[A] = Val(value, None)
    def err[A](value: A, msg: String): Val[A] = Val(value, Some(msg))
  }

  case class Val[A] private(v: A, err: Option[String]) {
    def with_err(err: String): Val[A] = Val.err(v, err)
    def perhaps_err(opt: Val_Opt[_]): Val[A] = opt.err.map(with_err).getOrElse(this)
  }


  /* data model and operations */

  object Model {
    sealed trait T

    object Related extends Enumeration {
      val DOI, Plaintext = Value

      def from_string(s: String): Option[Value] = values.find(_.toString == s)
      def get(r: Reference): Value = r match {
        case afp.Metadata.DOI(_) => DOI
        case afp.Metadata.Formatted(_) => Plaintext
      }
    }

    object Create_Entry {
      def apply(state: State): Create_Entry = Create_Entry(license = state.licenses.values.head)
    }

    case class Create_Entry(
      name: Val[String] = Val.ok(""),
      title: Val[String] = Val.ok(""),
      affils: Val[List[Affiliation]] = Val.ok(Nil),
      notifies: Val[List[Email]] = Val.ok(Nil),
      author_input: Option[Author] = None,
      notify_input: Option[Author] = None,
      topics: Val[List[Topic]] = Val.ok(Nil),
      topic_input: Option[Topic] = None,
      license: License,
      `abstract`: Val[String] = Val.ok(""),
      related: List[Reference] = Nil,
      related_kind: Option[Related.Value] = None,
      related_input: Val[String] = Val.ok("")
    ) {
      def used_affils: Set[Affiliation] = (affils.v ++ notifies.v).toSet
      def used_authors: Set[Author.ID] = used_affils.map(_.author)

      def add_affil: Create_Entry =
        author_input match {
          case None => copy(affils = affils.with_err("Select author first"))
          case Some(author) =>
            val default_affil = author.emails.headOption.orElse(
              author.homepages.headOption).getOrElse(Unaffiliated(author.id))

            copy(author_input = None, affils = Val.ok(affils.v :+ default_affil))
        }

      def remove_affil(affil: Affiliation): Create_Entry =
        copy(affils = Val.ok(affils.v.filterNot(_ == affil)))

      def add_notify: Option[Create_Entry] =
        notify_input match {
          case None => Some(copy(notifies = notifies.with_err("Select author first")))
          case Some(author) =>
            for (email <- author.emails.headOption)
            yield copy(notify_input = None, notifies = Val.ok(notifies.v :+ email))
        }

      def remove_notify(notify: Email): Create_Entry =
        copy(notifies = Val.ok(notifies.v.filterNot(_ == notify)))

      def add_topic(state: State): Create_Entry =
        topic_input match {
          case None => copy(topics = topics.with_err("Select topic first"))
          case Some(topic) =>
            val topic1 = Model.validate_topic(topic.id, topics.v, state)
            val topic_input1 = if (topic1.is_empty) topic_input else None
            copy(topic_input = topic_input1, topics =
              Val.ok(topics.v ++ topic1.opt).perhaps_err(topic1))
        }

      def remove_topic(topic: Topic): Create_Entry =
        copy(topics = Val.ok(topics.v.filterNot(_ == topic)))

      def add_related: Create_Entry =
        related_kind match {
          case None =>
            copy(related_input = related_input.with_err("Select reference kind first"))
          case Some(kind) =>
            val reference = validate_related(kind, related_input.v, entry.related)
            copy(related = related ++ reference.opt, related_input =
              Val.ok(if (reference.is_empty) related_input.v else "").perhaps_err(reference))
        }

      def remove_related(reference: Reference): Create_Entry =
        copy(related = related.filterNot(_ == reference))

      def entry: Entry =
        Entry(name = name.v, title = title.v, authors = affils.v, date = LocalDate.now(),
          topics = topics.v, `abstract` = `abstract`.v.trim, notifies = notifies.v,
          license = license, note = "", related = related)
    }

    object Create {
      def init(state: State): Create = Create(entries = Val.ok(List(Create_Entry(state))))
    }

    case class Create(
      entries: Val[List[Create_Entry]] = Val.ok(Nil),
      new_authors: Val[List[Author]] = Val.ok(Nil),
      new_author_input: String = "",
      new_author_orcid: String = "",
      new_affils: Val[List[Affiliation]] = Val.ok(Nil),
      new_affils_author: Option[Author] = None,
      new_affils_input: String = "",
    ) extends T {
      def add_entry(state: State): Create =
        copy(entries = Val.ok(entries.v :+ Create_Entry(state)))

      def update_entry(num: Int, entry: Create_Entry): Create =
        copy(entries = Val.ok(entries.v.updated(num, entry)))

      def remove_entry(num: Int): Create =
        copy(entries = Val.ok(Utils.remove_at(num, entries.v)))

      def updated_authors(state: State): Authors =
        (state.authors ++ new_authors.v.map(author => author.id -> author)).map {
          case (id, author) =>
            val emails =
              author.emails ++ new_affils.v.collect { case e: Email if e.author == id => e }
            val homepages =
              author.homepages ++ new_affils.v.collect { case h: Homepage if h.author == id => h }
            id -> author.copy(emails = emails.distinct, homepages = homepages.distinct)
        }

      def add_new_author(state: State): Create = {
        val name = new_author_input.trim
        if (name.isEmpty) copy(new_authors = new_authors.with_err("Name must not be empty"))
        else {
          def as_ascii(str: String) = {
            var res: String = str
            List("ö" -> "oe", "ü" -> "ue", "ä" -> "ae", "ß" -> "ss").foreach {
              case (c, rep) => res = res.replace(c, rep)
            }
            Normalizer.normalize(res, Normalizer.Form.NFD).replaceAll("[^\\x00-\\x7F]", "")
          }

          def make_author_id(name: String): String = {
            val normalized = as_ascii(name)
            val Suffix = """^.*?([a-zA-Z]*)$""".r
            val suffix =
              normalized match {
                case Suffix(suffix) => suffix
                case _ => ""
              }
            val Prefix = """^([a-zA-Z]*).*$""".r
            val prefix =
              normalized.stripSuffix(suffix) match {
                case Prefix(prefix) => prefix
                case _ => ""
              }
            val authors = updated_authors(state)

            var ident = suffix.toLowerCase
            for {
              c <- prefix.toLowerCase
              if authors.contains(ident)
            } ident += c.toString

            Utils.make_unique(ident, authors.keySet)
          }

          val id = make_author_id(name)

          val author =
            validate_new_author(id, new_author_input, new_author_orcid, updated_authors(state))

          copy(
            new_author_input = if (author.is_empty) new_author_input else "",
            new_author_orcid = if (author.is_empty) new_author_orcid else "",
            new_authors = Val.ok(new_authors.v ++ author.opt).perhaps_err(author))
        }
      }

      def remove_new_author(author: Author): Option[Create] =
        if (used_authors.contains(author.id)) None
        else Some(copy(new_authors = Val.ok(new_authors.v.filterNot(_.id == author.id))))

      def add_new_affil: Create =
        new_affils_author match {
          case Some(author) =>
            val address = new_affils_input.trim
            if (address.isEmpty) copy(new_affils = new_affils.with_err("Must not be empty"))
            else {
              val id =
                if (address.contains("@"))
                  Utils.make_unique(author.id + "_email", author.emails.map(_.id).toSet)
                else
                  Utils.make_unique(author.id + "_homepage", author.homepages.map(_.id).toSet)

              val affil = validate_new_affil(id, address, author)
              copy(
                new_affils_input = if (affil.is_empty) new_affils_input else "",
                new_affils = Val.ok(new_affils.v ++ affil.opt).perhaps_err(affil))
            }
          case None => copy(new_affils = new_affils.with_err("Select author first"))
        }

      def remove_new_affil(affil: Affiliation): Option[Create] =
        if (used_affils.contains(affil)) None
        else Some(copy(new_affils = Val.ok(new_affils.v.filterNot(_ == affil))))

      def validate(
        state: State,
        message: String,
        existing: Boolean
      ): T = {
        var ok = true

        def validate[A](validator: A => Val[A], value: A): Val[A] = {
          val res = validator(value)
          if (res.err.nonEmpty) ok = false
          res
        }

        def val_title(title: String): Val[String] =
          if (title.isBlank) Val.err(title, "Title must not be blank")
          else if (title.trim != title) Val.err(title, "Title must not contain extra spaces")
          else Val.ok(title)

        def val_name(name: String): Val[String] =
          if (name.isBlank) Val.err(name, "Name must not be blank")
          else if (!"[a-zA-Z0-9_-]+".r.matches(name))
            Val.err(name, "Invalid character in name")
          else if (existing && !state.entries.contains(name))
            Val.err(name, "Entry does not exist")
          else if (!existing && state.entries.contains(name))
            Val.err(name, "Entry already exists")
          else Val.ok(name)

        def val_entries(entries: List[Model.Create_Entry]): Val[List[Model.Create_Entry]] =
          if (entries.isEmpty) Val.err(entries, "Must contain at least one entry")
          else if (Library.duplicates(entries.map(_.name)).nonEmpty)
            Val.err(entries, "Entries must have different names")
          else Val.ok(entries)

        def val_new_authors(authors: List[Author]): Val[List[Author]] =
          if (!authors.forall(author => used_authors.contains(author.id)))
            Val.err(authors, "Unused authors")
          else Val.ok(authors)

        def val_new_affils(affils: List[Affiliation]): Val[List[Affiliation]] =
          if (!affils.forall(affil => used_affils.contains(affil)))
            Val.err(affils, "Unused affils")
          else Val.ok(affils)

        def val_entry_authors(authors: List[Affiliation]): Val[List[Affiliation]] =
          if (authors.isEmpty) Val.err(authors, "Must contain at least one author")
          else if (!Utils.is_distinct(authors)) Val.err(authors, "Duplicate affiliations")
          else Val.ok(authors)

        def val_notifies(notifies: List[Email]): Val[List[Email]] =
          if (notifies.isEmpty) Val.err(notifies, "Must contain at least one maintainer")
          else if (!Utils.is_distinct(notifies)) Val.err(notifies, "Duplicate emails")
          else Val.ok(notifies)

        def val_topics(topics: List[Topic]): Val[List[Topic]] =
          if (topics.isEmpty) Val.err(topics, "Must contain at least one topic") else Val.ok(topics)

        def val_abstract(txt: String): Val[String] =
          if (txt.isBlank) Val.err(txt, "Entry must contain an abstract")
          else if (List("\\cite", "\\emph", "\\texttt").exists(txt.contains(_)))
            Val.err(txt, "LaTeX not allowed, use MathJax for math symbols")
          else Val.ok(txt)

        val entries1 =
          for (entry <- entries.v)
          yield entry.copy(
            name = validate(val_name, entry.name.v),
            title = validate(val_title, entry.title.v),
            affils = validate(val_entry_authors, entry.affils.v),
            notifies = validate(val_notifies, entry.notifies.v),
            topics = validate(val_topics, entry.topics.v),
            `abstract` = validate(val_abstract, entry.`abstract`.v))

        val validated =
          copy(
            entries = validate(val_entries, entries1),
            new_authors = validate(val_new_authors, new_authors.v),
            new_affils = validate(val_new_affils, new_affils.v))

        if (ok) Upload(Metadata(updated_authors(state), entries.v.map(_.entry)), message)
        else validated
      }

      def used_affils: Set[Affiliation] = entries.v.toSet.flatMap(_.used_affils)
      def used_authors: Set[Author.ID] =
        new_affils.v.map(_.author).toSet ++ entries.v.flatMap(_.used_authors)
    }

    object Build extends Enumeration {
      val Pending, Running, Aborted, Failed, Success = Value
    }

    object Status extends Enumeration {
      val Submitted, Review, Added, Rejected = Value

      def from_string(s: String): Option[Value] = values.find(_.toString == s)
    }

    case class Overview(id: String, date: LocalDate, name: String, status: Status.Value) {
      def update_repo(repo: Mercurial.Repository): Boolean =
        if (status != Model.Status.Added) false
        else {
          val id_before = repo.id()
          repo.pull()
          repo.update()
          val id_after = repo.id()
          id_before != id_after
        }
    }

    case class Metadata(authors: Authors, entries: List[Entry]) {
      def new_authors(state: State): Set[Author] =
        entries.flatMap(_.authors).map(_.author).filterNot(state.authors.contains).toSet.map(authors)

      def new_affils(state: State): Set[Affiliation] =
        entries.flatMap(entry => entry.authors ++ entry.notifies).toSet.filter {
          case _: Unaffiliated => false
          case e: Email => !state.authors.get(e.author).exists(_.emails.contains(e))
          case h: Homepage => !state.authors.get(h.author).exists(_.homepages.contains(h))
        }
    }

    case object Invalid extends T

    object Upload {
      def apply(submission: Submission): Upload = Upload(submission.meta, submission.message)
    }

    case class Upload(metadata: Metadata, message: String, error: String = "") extends T {
      def save(handler: Handler, state: State): (Submission, State) = {
        val (id, state1) = handler.save(state, metadata, message)
        (handler.get(id, state1).get, state1)
      }

      def submit(handler: Handler, bytes: String, file_name: String, state: State): (T, State) = {
        val archive = Bytes.decode_base64(bytes)

        if (archive.is_empty || file_name.isEmpty) (copy(error = "Select a file"), state)
        else if (!file_name.endsWith(".zip") && !file_name.endsWith(".tar.gz"))
          (copy(error = "Only .zip and .tar.gz archives allowed"), state)
        else {
          val file_extension = if (file_name.endsWith(".zip")) ".zip" else ".tar.gz"
          val (id, state1) = handler.save(state, metadata, message, archive, file_extension)
          (Created(id), state1)
        }
      }
    }

    case class Created(id: String) extends T

    case class Submission(
      id: Handler.ID,
      meta: Metadata,
      build: Build.Value,
      status: Option[Status.Value],
      message: String = "",
      log: Option[String] = None,
      archive: Option[String] = None
    ) extends T {
      def submit(handler: Handler): Option[Submission] =
        if (status.nonEmpty) None
        else {
          handler.submit(id)
          Some(copy(status = Some(Status.Submitted)))
        }

      def abort_build(handler: Handler): Option[Submission] =
        if (build != Model.Build.Running) None
        else {
          handler.abort_build(id)
          Some(copy(build = Model.Build.Aborted))
        }
    }

    case class Submission_List(submissions: List[Overview]) extends T


    /* validation */

    def validate_topic(id: String, selected: List[Topic], state: State): Val_Opt[Topic] =
      state.topics.values.find(_.id == id) match {
        case Some(topic) =>
          if (selected.contains(topic)) Val_Opt.user_err("Topic already selected")
          else Val_Opt.ok(topic)
        case _ => Val_Opt.error
      }

    def validate_new_author(
      id: String,
      name: String,
      orcid_str: String,
      authors: Authors
    ): Val_Opt[Author] = {
      val Id = """[a-zA-Z][a-zA-Z0-9]*""".r
      id match {
        case Id() if !authors.contains(id) =>
          if (name.isEmpty || name.trim != name)
            Val_Opt.user_err("Name must not be empty")
          else if (orcid_str.isEmpty)
            Val_Opt.ok(Author(id, name))
          else Exn.capture(Orcid(orcid_str)) match {
            case Exn.Res(orcid) =>
              if (authors.values.exists(_.orcid.exists(_ == orcid)))
                Val_Opt.user_err("Author with that orcid already exists")
              else Val_Opt.ok(Author(id, name, orcid = Some(orcid)))
            case _ => Val_Opt.user_err("Invalid orcid")
          }
        case _ => Val_Opt.error
      }
    }

    def validate_new_affil(id: String, address: String, author: Author): Val_Opt[Affiliation] = {
      if (address.isBlank) Val_Opt.user_err("Address must not be empty")
      else if (address.contains("@")) {
        val Id = (author.id + """_email\d*""").r
        id match {
          case Id() if !author.emails.map(_.id).contains(id) =>
            val Address = """([^@\s]+)@([^@\s]+)""".r
            address match {
              case Address(user, host) => Val_Opt.ok(Email(author.id, id, user, host))
              case _ => Val_Opt.user_err("Invalid email address")
            }
          case _ => Val_Opt.error
        }
      }
      else {
        val Id = (author.id + """_homepage\d*""").r
        id match {
          case Id() if !author.homepages.map(_.id).contains(id) =>
            if (Url.is_wellformed(address)) Val_Opt.ok(Homepage(author.id, id, Url(address)))
            else Val_Opt.user_err("Invalid url")
          case _ => Val_Opt.error
        }
      }
    }

    def validate_related(
      kind: Model.Related.Value,
      related: String,
      references: List[Reference]
    ): Val_Opt[Reference] =
      if (related.isBlank) Val_Opt.user_err("Reference must not be empty")
      else {
        kind match {
          case Model.Related.DOI =>
            Exn.capture(DOI(related)) match {
              case Exn.Res(doi) =>
                if (references.contains(doi)) Val_Opt.user_err("Already present")
                else Val_Opt.ok(doi)
              case _ => Val_Opt.user_err("Invalid DOI format")
            }
          case Model.Related.Plaintext =>
            val formatted = Formatted(related)
            if (references.contains(formatted)) Val_Opt.user_err("Already present")
            else Val_Opt.ok(formatted)
        }
      }
  }


  /* Physical submission handling */

  trait Handler {
    def save(
      state: State,
      metadata: Model.Metadata,
      message: String = "",
      archive: Bytes = Bytes.empty,
      file_extension: String = ""
    ): (Handler.ID, State)
    def list(state: State): Model.Submission_List
    def get(id: Handler.ID, state: State): Option[Model.Submission]
    def submit(id: Handler.ID): Unit
    def set_status(id: Handler.ID, status: Model.Status.Value): Unit
    def abort_build(id: Handler.ID): Unit
    def get_patch(id: Handler.ID): Option[Path]
    def get_archive(id: Handler.ID): Option[Path]
  }

  object Handler {
    type ID = String

    object ID {
      private val format = Date.Format.make(
        List(
          Date.Formatter.pattern("uuuu-MM-dd_HH-mm-ss_SSS"),
          Date.Formatter.pattern("uuuu-MM-dd_HH-mm-ss_SSS_VV")),
        _ + "_" + Date.timezone().getId)

      def apply(submission_time: Date): ID = format(submission_time)
      def unapply(id: ID): Option[Date] = format.unapply(id)
      def check(id: ID): Option[ID] = unapply(id).map(apply)
    }


    /* Handler for local edits */

    class Edit(afp: AFP_Structure) extends Handler {
      def save(
        state: State,
        metadata: Model.Metadata,
        message: String,
        archive: Bytes,
        ext: String
      ): (ID, State) = {
        val entry =
          metadata.entries match {
            case e :: Nil => e
            case _ => isabelle.error("Must be a single entry")
          }

        val old = state.entries(entry.name)
        val updated =
          old.copy(title = entry.title, authors = entry.authors, topics = entry.topics,
            `abstract` = entry.`abstract`, notifies = entry.notifies, license = entry.license,
            related = entry.related)

        afp.save_entry(updated)
        afp.save_authors(metadata.authors.values.toList)

        (entry.name, State.load(afp))
      }

      def list(state: State): Model.Submission_List =
        Model.Submission_List(state.entries.values.toList.sortBy(_.date).reverse.map { entry =>
          Model.Overview(entry.name, entry.date, entry.name, Model.Status.Added)
        })

      def get(id: ID, state: State): Option[Model.Submission] =
        state.entries.get(id).map { entry =>
          val meta = Model.Metadata(state.authors, List(entry))
          Model.Submission(id, meta, Model.Build.Success, Some(Model.Status.Added))
        }

      def submit(id: ID): Unit = ()
      def set_status(id: ID, status: Model.Status.Value): Unit = ()
      def abort_build(id: ID): Unit = ()
      def get_patch(id: ID): Option[Path] = None
      def get_archive(id: ID): Option[Path] = None
    }


    /* Adapter to existing submission system */

    class Adapter(submission_dir: Path, afp: AFP_Structure) extends Handler {
      private val up: Path = submission_dir + Path.basic("up")
      private def up(id: ID): Path = up + Path.basic(id)
      private def down(id: ID): Path = submission_dir + Path.basic("down") + Path.basic(id)

      private def signal(id: ID, s: String): Unit =
        File.write(up(id) + Path.basic(s), s.toUpperCase)
      private def is_signal(id: ID, s: String): Boolean = (up(id) + Path.basic(s)).file.exists()

      private def read_build(id: ID): Model.Build.Value = {
        val build = down(id) + Path.basic("result")
        if (!build.file.exists) Model.Build.Pending
        else File.read(build).trim match {
          case "" => Model.Build.Running
          case "NOT_FINISHED" => Model.Build.Running
          case "FAILED" => if (is_signal(id, "kill")) Model.Build.Aborted else Model.Build.Failed
          case "SUCCESS" => Model.Build.Success
          case s => isabelle.error("Unkown build status: " + quote(s))
        }
      }

      private def status_file(id: ID): Path = up(id) + Path.basic("AFP_STATUS")
      private def read_status(id: ID): Option[Model.Status.Value] = {
        val status = status_file(id)
        if (!status.file.exists()) None
        else File.read(status).trim match {
          case "SUBMITTED" => Some(Model.Status.Submitted)
          case "PROCESSING" => Some(Model.Status.Review)
          case "REJECTED" => Some(Model.Status.Rejected)
          case "ADDED" => Some(Model.Status.Added)
          case s => isabelle.error("Unknown status: " + quote(s))
        }
      }

      private def info_file(id: ID): Path = up(id) + Path.basic("info.json")
      private def patch_file(id: ID): Path = up(id) + Path.basic("patch")

      private val archive_name: String = "archive"

      def make_partial_patch(base_dir: Path, src: Path, dst: Path): String = {
        val patch = Isabelle_System.make_patch(base_dir, src, dst, "--unidirectional-new-file")
        split_lines(patch).filterNot(_.startsWith("Only in")).mkString("\n")
      }

      def save(
        state: State,
        metadata: Model.Metadata,
        message: String,
        archive: Bytes,
        file_extension: String
      ): (ID, State) = {
        val id = ID(Date.now())
        val dir = up(id)
        dir.file.mkdirs()

        val structure = AFP_Structure(dir)
        structure.save_authors(metadata.authors.values.toList.sortBy(_.id))
        metadata.entries.foreach(structure.save_entry)

        val archive_file = dir + Path.basic(archive_name + file_extension)
        Bytes.write(archive_file, archive)

        val metadata_rel =
          File.relative_path(afp.base_dir, afp.metadata_dir).getOrElse(afp.metadata_dir)
        val metadata_patch = make_partial_patch(afp.base_dir, metadata_rel, structure.metadata_dir)
        File.write(patch_file(id), metadata_patch)

        val info =
          JSON.Format(JSON.Object(
            "comment" -> message,
            "entries" -> metadata.entries.map(_.name),
            "notify" -> metadata.entries.flatMap(_.notifies).map(_.address).distinct))
        File.write(info_file(id), info)

        signal(id, "done")
        (id, state)
      }

      def list(state: State): Model.Submission_List =
        Model.Submission_List(
          File.read_dir(up).flatMap(ID.unapply).reverse.flatMap { date =>
            val id = ID(date)
            val day = date.rep.toLocalDate
            read_status(id).map(
              Model.Overview(id, day, AFP_Structure(up(id)).entries_unchecked.head, _))
          })

      def get(id: ID, state: State): Option[Model.Submission] =
        ID.check(id).filter(up(_).file.exists).map { id =>
          val structure = AFP_Structure(up(id))
          val authors = structure.load_authors
          val entries = structure.entries_unchecked.map(
            structure.load_entry(_, authors, state.topics, state.licenses, state.releases))

          val log_file = down(id) + Path.basic("isabelle.log")
          val log = if (log_file.file.exists) Some(File.read(log_file)) else None
          val archive = get_archive(id).map(_.file_name)

          JSON.parse(File.read(info_file(id))) match {
            case JSON.Object(m) if m.contains("comment") =>
              val comment = m("comment").toString
              val meta = Model.Metadata(authors, entries)
              Model.Submission(id, meta, read_build(id), read_status(id), comment, log, archive)
            case _ => isabelle.error("Could not read info")
          }
        }

      private def check(id: ID): Option[ID] = ID.check(id).filter(up(_).file.exists)

      def submit(id: ID): Unit = check(id).foreach(signal(_, "mail"))

      def set_status(id: ID, status: Model.Status.Value): Unit =
        check(id).foreach { id =>
          val content =
            status match {
              case Model.Status.Submitted => "SUBMITTED"
              case Model.Status.Review => "PROCESSING"
              case Model.Status.Added => "ADDED"
              case Model.Status.Rejected => "REJECTED"
            }
          File.write(status_file(id), content)
        }

      def abort_build(id: ID): Unit = check(id).foreach(signal(_, "kill"))

      def get_patch(id: ID): Option[Path] = check(id).map(patch_file)
      def get_archive(id: ID): Option[Path] = check(id).flatMap { id =>
        val dir = up(id)
        File.read_dir(dir).find(_.startsWith(archive_name + ".")).map(dir + Path.basic(_))
      }
    }
  }


  /* paths */

  object Page {
    val SUBMIT = Path.explode("submit")
    val SUBMISSION = Path.explode("submission")
    val SUBMISSIONS = Path.explode("submissions")
  }

  object API {
    val SUBMISSION = Path.explode("api/submission")
    val SUBMISSION_UPLOAD = Path.explode("api/submission/upload")
    val SUBMISSION_CREATE = Path.explode("api/submission/create")
    val SUBMISSION_STATUS = Path.explode("api/submission/status")
    val RESUBMIT = Path.explode("api/resubmit")
    val BUILD_ABORT = Path.explode("api/build/abort")
    val SUBMIT = Path.explode("api/submit")
    val SUBMISSION_AUTHORS_ADD = Path.explode("api/submission/authors/add")
    val SUBMISSION_AUTHORS_REMOVE = Path.explode("api/submission/authors/remove")
    val SUBMISSION_AFFILIATIONS_ADD = Path.explode("api/submission/affiliations/add")
    val SUBMISSION_AFFILIATIONS_REMOVE = Path.explode("api/submission/affiliations/remove")
    val SUBMISSION_ENTRIES_ADD = Path.explode("api/submission/entries/add")
    val SUBMISSION_ENTRIES_REMOVE = Path.explode("api/submission/entries/remove")
    val SUBMISSION_ENTRY_TOPICS_ADD = Path.explode("api/submission/entry/topics/add")
    val SUBMISSION_ENTRY_TOPICS_REMOVE = Path.explode("api/submission/entry/topics/remove")
    val SUBMISSION_ENTRY_AUTHORS_ADD = Path.explode("api/submission/entry/authors/add")
    val SUBMISSION_ENTRY_AUTHORS_REMOVE = Path.explode("api/submission/entry/authors/remove")
    val SUBMISSION_ENTRY_NOTIFIES_ADD = Path.explode("api/submission/entry/notifies/add")
    val SUBMISSION_ENTRY_NOTIFIES_REMOVE = Path.explode("api/submission/entry/notifies/remove")
    val SUBMISSION_ENTRY_RELATED_ADD = Path.explode("api/submission/entry/related/add")
    val SUBMISSION_ENTRY_RELATED_REMOVE = Path.explode("api/submission/entry/related/remove")
    val SUBMISSION_DOWNLOAD = Path.explode("api/download/patch")
    val SUBMISSION_DOWNLOAD_ZIP = Path.explode("api/download/archive.zip")
    val SUBMISSION_DOWNLOAD_TGZ = Path.explode("api/download/archive.tar.gz")
    val CSS = Path.explode("api/main.css")
  }


  /* view: html rendering and parameter parsing */

  class View(paths: Web_App.Paths, mode: Mode.Value) {
    /* keys */

    private val ABSTRACT = "abstract"
    private val ADDRESS = "address"
    private val AFFILIATION = "affiliation"
    private val ARCHIVE = "archive"
    private val AUTHOR = "author"
    private val DATE = "date"
    private val ENTRY = "entry"
    private val ID = "id"
    private val INPUT = "input"
    private val KIND = "kind"
    private val LICENSE = "license"
    private val MESSAGE = "message"
    private val NAME = "name"
    private val NOTIFY = "notify"
    private val ORCID = "orcid"
    private val RELATED = "related"
    private val STATUS = "status"
    private val TITLE = "title"
    private val TOPIC = "topic"


    /* utils */

    def keyed(id: String, value: String): String = "[" + id + "] " + value

    def author_string(author: Author): String = {
      val orcid =
        author.orcid.map(orcid => " (ORCID id: " + orcid.identifier + ")").getOrElse("")
      keyed(author.id, author.name) + orcid
    }

    def author_option(author: Author): XML.Elem = option(author.id, author_string(author))

    def affil_id(affil: Affiliation): String =
      affil match {
        case Unaffiliated(_) => ""
        case Email(_, id, _) => id
        case Homepage(_, id, _) => id
      }

    def affil_address(affil: Affiliation): String =
      affil match {
        case Unaffiliated(_) => ""
        case Email(_, _, address) => address
        case Homepage(_, _, url) => url.toString
      }

    def affil_string(affil: Affiliation): String =
      affil match {
        case Unaffiliated(_) => "No email or homepage"
        case Email(_, id, address) => keyed(id, address)
        case Homepage(_, id, url) => keyed(id, url.toString)
      }

    def related_string(related: Reference): String = related match {
      case Metadata.DOI(identifier) => identifier
      case Metadata.Formatted(rep) => rep
    }

    def indexed[A, B](l: List[A], key: Params.Key, field: String, f: (A, Params.Key) => B) =
      l.zipWithIndex map {
        case (a, i) => f(a, List_Key(key, field, i))
      }

    def fold[A](it: List[Params.Data], a: A)(f: (Params.Data, A) => Option[A]): Option[A] =
      it.foldLeft(Option(a)) {
        case (None, _) => None
        case (Some(a), param) => f(param, a)
      }

    def download_link(href: String, body: XML.Body): XML.Elem =
      class_("download")(link(href, body)) + ("target" -> "_blank")
    def frontend_link(path: Path, params: Properties.T, body: XML.Body): XML.Elem =
      link(paths.frontend_url(path, params).toString, body) + ("target" -> "_parent")

    def render_if(cond: Boolean, body: XML.Body): XML.Body = if (cond) body else Nil
    def render_if(cond: Boolean, elem: XML.Elem): XML.Body = if (cond) List(elem) else Nil
    def render_error(for_elem: String, validated: Val[_]): XML.Body =
      validated.err.map(error =>
        break ::: List(css("color: red")(label(for_elem, error)))).getOrElse(Nil)

    def render_metadata(metadata: Model.Metadata, state: State): XML.Body = {
      def render_topic(topic: Topic, key: Params.Key): XML.Elem =
        item(hidden(Nest_Key(key, ID), topic.id) :: text(topic.id))

      def render_affil(affil: Affiliation, key: Params.Key): XML.Elem =
        item(
          hidden(Nest_Key(key, ID), affil.author) ::
          hidden(Nest_Key(key, AFFILIATION), affil_id(affil)) ::
          text(author_string(metadata.authors(affil.author)) + ", " + affil_string(affil)))

      def render_related(related: Reference, key: Params.Key): XML.Elem =
        item(
          hidden(Nest_Key(key, KIND), Model.Related.get(related).toString) ::
          hidden(Nest_Key(key, INPUT), related_string(related)) ::
          input_raw(related_string(related)) :: Nil)

      def render_entry(entry: Entry, key: Params.Key): XML.Elem =
        fieldset(List(
          legend("Entry"),
          par(fieldlabel(Nest_Key(key, TITLE), "Title") ::
            hidden(Nest_Key(key, TITLE), entry.title) ::
            text(entry.title)),
          par(fieldlabel(Nest_Key(key, NAME), "Short Name") ::
            hidden(Nest_Key(key, NAME), entry.name) ::
            text(entry.name)),
          par(fieldlabel(Nest_Key(key, DATE), "Date") ::
            hidden(Nest_Key(key, DATE), entry.date.toString) ::
            text(entry.date.toString)),
          par(List(fieldlabel("", "Topics"),
            list(indexed(entry.topics, key, TOPIC, render_topic)))),
          par(fieldlabel(Nest_Key(key, LICENSE), "License") ::
            hidden(Nest_Key(key, LICENSE), entry.license.id) ::
            text(entry.license.name)),
          par(List(fieldlabel(Nest_Key(key, ABSTRACT), "Abstract"),
            hidden(Nest_Key(key, ABSTRACT), entry.`abstract`),
            class_("mathjax_process")(span(List(input_raw(entry.`abstract`)))))),
          par(List(fieldlabel("", "Authors"),
            list(indexed(entry.authors, key, AUTHOR, render_affil)))),
          par(List(fieldlabel("", "Contact"),
            list(indexed(entry.notifies, key, NOTIFY, render_affil)))),
          par(List(fieldlabel("", "Related Publications"),
            list(indexed(entry.related, key, RELATED, render_related))))))

      def render_new_author(author: Author, key: Params.Key): XML.Elem =
        par(List(
          hidden(Nest_Key(key, ID), author.id),
          hidden(Nest_Key(key, NAME), author.name),
          hidden(Nest_Key(key, ORCID), author.orcid.map(_.identifier).getOrElse(""))))

      def render_new_affil(affil: Affiliation, key: Params.Key): XML.Elem =
        par(List(
          hidden(Nest_Key(key, AUTHOR), affil.author),
          hidden(Nest_Key(key, ID), affil_id(affil)),
          hidden(Nest_Key(key, AFFILIATION), affil_address(affil))))

      indexed(metadata.entries, Params.empty, ENTRY, render_entry) :::
        indexed(metadata.new_authors(state).toList, Params.empty, AUTHOR, render_new_author) :::
        indexed(metadata.new_affils(state).toList, Params.empty, AFFILIATION, render_new_affil)
    }


    /* rendering */

    def render_create(model: Model.Create, state: State): XML.Body = {
      val updated_authors = model.updated_authors(state)
      val authors_list = updated_authors.values.toList.sortBy(_.id)
      val (entry_authors, other_authors) =
        updated_authors.values.toList.sortBy(_.id).partition(author =>
          model.used_authors.contains(author.id))
      val email_authors = authors_list.filter(_.emails.nonEmpty)

      def render_topic(topic: Topic, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, ID), topic.id) ::
          text(topic.id) :::
          action_button(paths.api_route(API.SUBMISSION_ENTRY_TOPICS_REMOVE), "x", key) :: Nil)

      def render_affil(affil: Affiliation, key: Params.Key): XML.Elem = {
        val author = updated_authors(affil.author)
        val affils = author.emails ::: author.homepages ::: Unaffiliated(author.id) :: Nil
        par(
          hidden(Nest_Key(key, ID), affil.author) ::
            text(author_string(updated_authors(affil.author))) :::
            selection(Nest_Key(key, AFFILIATION),
              Some(affil_id(affil)),
              affils.map(affil => option(affil_id(affil), affil_string(affil)))) ::
            action_button(paths.api_route(API.SUBMISSION_ENTRY_AUTHORS_REMOVE), "x", key) :: Nil)
      }

      def render_notify(email: Email, key: Params.Key): XML.Elem = {
        val author = updated_authors(email.author)
        par(
          hidden(Nest_Key(key, ID), email.author) ::
            text(author_string(updated_authors(email.author))) :::
            selection(
              Nest_Key(key, AFFILIATION),
              Some(affil_id(email)),
              author.emails.map(affil => option(affil_id(affil), affil_string(affil)))) ::
            action_button(paths.api_route(API.SUBMISSION_ENTRY_NOTIFIES_REMOVE), "x", key) :: Nil)
      }

      def render_related(related: Reference, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, KIND), Model.Related.get(related).toString) ::
          hidden(Nest_Key(key, INPUT), related_string(related)) ::
          text(related_string(related)) :::
          action_button(paths.api_route(API.SUBMISSION_ENTRY_RELATED_REMOVE), "x", key) :: Nil)

      def render_entry(entry: Model.Create_Entry, key: Params.Key): XML.Elem =
        fieldset(legend("Entry") ::
          par(
            fieldlabel(Nest_Key(key, TITLE), "Title of article") ::
            textfield(Nest_Key(key, TITLE), "Example Submission", entry.title.v) ::
            render_error(Nest_Key(key, TITLE), entry.title)) ::
          par(
            fieldlabel(Nest_Key(key, NAME), "Short name") ::
            textfield(Nest_Key(key, NAME), "Example_Submission", entry.name.v) ::
            explanation(Nest_Key(key, NAME),
              "Name of the folder where your ROOT and theory files reside.") ::
            render_error(Nest_Key(key, NAME), entry.name)) ::
          fieldset(legend("Topics") ::
            indexed(entry.topics.v, key, TOPIC, render_topic) :::
            selection(Nest_Key(key, TOPIC),
              entry.topic_input.map(_.id),
              state.topics.values.toList.map(topic => option(topic.id, topic.id))) ::
            action_button(paths.api_route(API.SUBMISSION_ENTRY_TOPICS_ADD), "add", key) ::
            render_error("", entry.topics)) ::
          par(List(
            fieldlabel(Nest_Key(key, LICENSE), "License"),
            radio(Nest_Key(key, LICENSE),
              entry.license.id,
              state.licenses.values.toList.map(license => license.id -> license.name)),
            explanation(Nest_Key(key, LICENSE),
              "Note: For LGPL submissions, please include the header \"License: LGPL\" in each file"))) ::
          par(
            fieldlabel(Nest_Key(key, ABSTRACT), "Abstract") ::
            placeholder("HTML and MathJax, no LaTeX")(
              textarea(Nest_Key(key, ABSTRACT), entry.`abstract`.v) +
                ("rows" -> "8") +
                ("cols" -> "70")) ::
            explanation(Nest_Key(key, ABSTRACT),
              "Note: You can use HTML or MathJax (not LaTeX!) to format your abstract.") ::
            render_error(Nest_Key(key, ABSTRACT), entry.`abstract`)) ::
          fieldset(legend("Authors") ::
            indexed(entry.affils.v, key, AUTHOR, render_affil) :::
            selection(Nest_Key(key, AUTHOR),
              entry.author_input.map(_.id),
              authors_list.map(author => option(author.id, author_string(author)))) ::
            action_button(paths.api_route(API.SUBMISSION_ENTRY_AUTHORS_ADD), "add", key) ::
            explanation(Nest_Key(key, AUTHOR),
              "Add an author from the list. Register new authors first below.") ::
            render_error(Nest_Key(key, AUTHOR), entry.affils)) ::
          fieldset(legend("Contact") ::
            indexed(entry.notifies.v, key, NOTIFY, render_notify) :::
            selection(Nest_Key(key, NOTIFY),
              entry.notify_input.map(_.id),
              optgroup("Suggested", email_authors.filter(author =>
                entry.used_authors.contains(author.id)).map(author_option)) ::
                email_authors.filter(author =>
                  !entry.used_authors.contains(author.id)).map(author_option)) ::
            action_button(paths.api_route(API.SUBMISSION_ENTRY_NOTIFIES_ADD), "add", key) ::
            explanation(Nest_Key(key, NOTIFY),
              "These addresses serve two purposes: " +
              "1. They are used to send you updates about the state of your submission. " +
              "2. They are the maintainers of the entry once it is accepted. " +
              "Typically this will be one or more of the authors.") ::
            render_error("", entry.notifies)) ::
          fieldset(legend("Related Publications") ::
            indexed(entry.related, key, RELATED, render_related) :::
            selection(Nest_Key(Nest_Key(key, RELATED), KIND),
              entry.related_kind.map(_.toString),
              Model.Related.values.toList.map(v => option(v.toString, v.toString))) ::
            textfield(Nest_Key(Nest_Key(key, RELATED), INPUT),
              "10.1109/5.771073", entry.related_input.v) ::
            action_button(paths.api_route(API.SUBMISSION_ENTRY_RELATED_ADD), "add", key) ::
            explanation(Nest_Key(Nest_Key(key, RELATED), INPUT),
              "Publications related to the entry, as DOIs (10.1109/5.771073) or plaintext (HTML)." +
              "Typically a publication by the authors describing the entry," +
              " background literature (articles, books) or web resources. ") ::
            render_error(Nest_Key(Nest_Key(key, RELATED), INPUT), entry.related_input)) ::
          render_if(mode == Mode.SUBMISSION,
            action_button(paths.api_route(API.SUBMISSION_ENTRIES_REMOVE), "remove entry", key)))

      def render_new_author(author: Author, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, ID), author.id) ::
          hidden(Nest_Key(key, NAME), author.name) ::
          hidden(Nest_Key(key, ORCID), author.orcid.map(_.identifier).getOrElse("")) ::
          text(author_string(author)) :::
          render_if(!model.used_authors.contains(author.id),
            action_button(paths.api_route(API.SUBMISSION_AUTHORS_REMOVE), "x", key)))

      def render_new_affil(affil: Affiliation, key: Params.Key): XML.Elem =
        par(
          hidden(Nest_Key(key, AUTHOR), affil.author) ::
          hidden(Nest_Key(key, ID), affil_id(affil)) ::
          hidden(Nest_Key(key, AFFILIATION), affil_address(affil)) ::
          text(author_string(updated_authors(affil.author)) + ": " + affil_string(affil)) :::
          render_if(!model.used_affils.contains(affil),
            action_button(paths.api_route(API.SUBMISSION_AFFILIATIONS_REMOVE), "x", key)))

      val (upload, preview) = mode match {
        case Mode.EDIT => ("Save", "preview and save >")
        case Mode.SUBMISSION => ("Upload", "preview and upload >")
      }

      List(submit_form(paths.api_route(API.SUBMISSION),
        indexed(model.entries.v, Params.empty, ENTRY, render_entry) :::
        render_error("", model.entries) :::
        render_if(mode == Mode.SUBMISSION,
          par(List(
            explanation("",
              "You can submit multiple entries at once. " +
              "Put the corresponding folders in the archive " +
              "and use the button below to add more input fields for metadata. "),
            api_button(paths.api_route(API.SUBMISSION_ENTRIES_ADD), "additional entry")))) ::: break :::
        fieldset(legend("New Authors") ::
          explanation("", "If you are new to the AFP, add yourself here.") ::
          indexed(model.new_authors.v, Params.empty, AUTHOR, render_new_author) :::
          fieldlabel(Nest_Key(AUTHOR, NAME), "Name") ::
          textfield(Nest_Key(AUTHOR, NAME), "Gerwin Klein", model.new_author_input) ::
          fieldlabel(Nest_Key(AUTHOR, ORCID), "ORCID id (optional)") ::
          textfield(Nest_Key(AUTHOR, ORCID), "0000-0002-1825-0097", model.new_author_orcid) ::
          api_button(paths.api_route(API.SUBMISSION_AUTHORS_ADD), "add") ::
          render_error("", model.new_authors)) ::
        fieldset(legend("New email or homepage") ::
          explanation("",
            "Add new email or homepages here. " +
            "If you would like to update an existing, " +
            "submit with the old one and write to the editors.") ::
          indexed(model.new_affils.v, Params.empty, AFFILIATION, render_new_affil) :::
          fieldlabel(AFFILIATION, "Author") ::
          selection(AFFILIATION,
            model.new_affils_author.map(_.id),
            optgroup("Entry authors", entry_authors.map(author_option)) ::
              other_authors.map(author_option)) ::
          fieldlabel(Nest_Key(AFFILIATION, ADDRESS), "Email or Homepage") ::
          textfield(Nest_Key(AFFILIATION, ADDRESS), "https://proofcraft.org",
            model.new_affils_input) ::
          api_button(paths.api_route(API.SUBMISSION_AFFILIATIONS_ADD), "add") ::
          render_error("", model.new_affils)) :: break :::
        fieldset(List(legend(upload),
          api_button(paths.api_route(API.SUBMISSION_UPLOAD), preview))) :: Nil))
    }

    def render_submission(submission: Model.Submission, state: State): XML.Body = {
      def status_text(status: Option[Model.Status.Value]): String =
        status.map {
          case Model.Status.Submitted => "AFP editors notified."
          case Model.Status.Review => "Review in progress."
          case Model.Status.Added => "Added to the AFP."
          case Model.Status.Rejected => "Submission rejected."
        } getOrElse
          "Draft saved. Check the logs for errors and warnings, " +
          "and submit to editors once successfully built."

      def render_archive(archive: Option[String]): XML.Body = {
        val archive_url =
          archive match {
            case Some(archive) if archive.endsWith(".zip") => Some(API.SUBMISSION_DOWNLOAD_ZIP)
            case Some(archive) if archive.endsWith(".tar.gz") => Some(API.SUBMISSION_DOWNLOAD_TGZ)
            case _ => None
          }
        archive_url match {
          case Some(url) =>
            List(download_link(paths.api_route(url, List(ID -> submission.id)), text("archive")))
          case None => Nil
        }
      }

      val resubmit = mode match {
        case Mode.EDIT => "Update"
        case Mode.SUBMISSION => "Resubmit"
      }

      def render_log(log: Option[String]): XML.Body =
        log match {
          case None => text("Waiting for build...")
          case Some(log) => text("Isabelle log:") ::: source(log) :: Nil
        }

      List(submit_form(paths.api_route(Page.SUBMISSION, List(ID -> submission.id)),
        render_if(mode == Mode.SUBMISSION,
          render_archive(submission.archive) :::
          download_link(paths.api_route(API.SUBMISSION_DOWNLOAD, List(ID -> submission.id)),
            text("metadata patch")) ::
          text(" (apply with: 'patch -p0 < FILE')")) :::
        render_if(mode == Mode.SUBMISSION, par(
          hidden(MESSAGE, submission.message) ::
          text("Comment: " + submission.message))) :::
        section("Metadata") ::
        render_metadata(submission.meta, state) :::
        section("Status") ::
        span(text(status_text(submission.status))) ::
        render_if(submission.build != Model.Build.Running,
          action_button(paths.api_route(API.RESUBMIT), resubmit, submission.id)) :::
        render_if(submission.build == Model.Build.Running,
          action_button(paths.api_route(API.BUILD_ABORT), "Abort build", submission.id)) :::
        render_if(submission.build == Model.Build.Success && submission.status.isEmpty,
          action_button(paths.api_route(API.SUBMIT), "Send submission to AFP editors", submission.id)) :::
        render_if(mode == Mode.SUBMISSION,
          fieldset(legend("Build") ::
            bold(text(submission.build.toString)) ::
            par(render_log(submission.log)) :: Nil))))
    }

    def render_upload(upload: Model.Upload, state: State): XML.Body = {
      val submit = mode match {
        case Mode.EDIT => "save"
        case Mode.SUBMISSION => "submit"
      }

      List(submit_form(paths.api_route(API.SUBMISSION), List(
        fieldset(legend("Overview") :: render_metadata(upload.metadata, state)),
        fieldset(legend("Submit") ::
          api_button(paths.api_route(API.SUBMISSION), "< edit metadata") ::
          render_if(mode == Mode.SUBMISSION, List(
            par(List(
              fieldlabel(MESSAGE, "Message for the editors (optional)"),
              textfield(MESSAGE, "", upload.message),
              explanation(
                MESSAGE,
                "Note: Anything special or noteworthy about your submission can be covered here. " +
                  "It will not become part of your entry. " +
                  "You're also welcome to leave suggestions for our AFP submission service here. ")
            )),
            par(List(
              fieldlabel(ARCHIVE, "Archive file (.tar.gz or .zip)"),
              browse(ARCHIVE, List(".zip", ".tar.gz")),
              explanation(ARCHIVE,
                "Note: Your zip or tar file must contain exactly one folder for each entry with your theories, ROOT, etc. " +
                "The folder name must be the short/folder name used in the submission form. " +
                "Hidden files and folders (e.g., __MACOSX) are not allowed."))))) :::
          api_button(paths.api_route(API.SUBMISSION_CREATE), submit) ::
          render_error(ARCHIVE, Val.err((), upload.error))))))
    }

    def render_submission_list(submission_list: Model.Submission_List): XML.Body = {
      def render_overview(overview: Model.Overview, key: Params.Key): XML.Elem =
        item(
          hidden(Nest_Key(key, ID), overview.id) ::
          hidden(Nest_Key(key, DATE), overview.date.toString) ::
          hidden(Nest_Key(key, NAME), overview.name) ::
          span(text(overview.date.toString)) ::
          span(List(frontend_link(Page.SUBMISSION, List(ID -> overview.id),
            text(overview.name)))) ::
          render_if(mode == Mode.SUBMISSION,
            class_("right")(span(List(
              selection(Nest_Key(key, STATUS), Some(overview.status.toString),
                Model.Status.values.toList.map(v => option(v.toString, v.toString))),
              action_button(paths.api_route(API.SUBMISSION_STATUS), "update", key))))))

      def list1(ls: List[XML.Elem]): XML.Elem = if (ls.isEmpty) par(Nil) else list(ls)

      val ls = indexed(submission_list.submissions, Params.empty, ENTRY, (o, k) => (o, k))
      val finished =
        ls.filter(t => Set(Model.Status.Added, Model.Status.Rejected).contains(t._1.status))

      List(submit_form(paths.api_route(API.SUBMISSION_STATUS),
        render_if(mode == Mode.SUBMISSION,
          text("Open") :::
          list1(ls.filter(_._1.status == Model.Status.Submitted).map(render_overview)) ::
          text("In Progress") :::
          list1(ls.filter(_._1.status == Model.Status.Review).map(render_overview)) ::
          text("Finished")) :::
        list1(finished.map(render_overview)) :: Nil))
    }

    def render_created(created: Model.Created): XML.Body = {
      val status = mode match {
        case Mode.SUBMISSION => "View your submission status: "
        case Mode.EDIT => "View the entry at: "
      }

      List(div(
        span(text("Entry successfully saved. " + status)) :: break :::
        frontend_link(Page.SUBMISSION, List(ID -> created.id),
          text(paths.frontend_url(Page.SUBMISSION, List(ID -> created.id)).toString)) :: break :::
        render_if(mode == Mode.SUBMISSION, span(text("(keep that url!).")))))
    }

    def render_invalid: XML.Body = text("Invalid request")

    def render_head: XML.Body =
      List(
        XML.Elem(Markup("script", List(
          "type" -> "text/javascript",
          "id" -> "MathJax-script",
          "async" -> "async",
          "src" -> "https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-svg.js")), text("\n")),
        script(
          "MathJax={tex:{inlineMath:[['$','$'],['\\\\(','\\\\)']]},processEscapes:true,svg:{fontCache:'global'}}"),
        style_file(paths.api_route(API.CSS)))


    /* param parsing */

    def parse_create(params: Params.Data, state: State): Option[Model.Create] = {
      def parse_topic(topic: Params.Data, topics: List[Topic]): Option[Topic] =
        Model.validate_topic(topic.get(ID).value, topics, state).opt

      def parse_email(email: Params.Data, authors: Authors): Option[Email] =
        authors.get(email.get(ID).value).flatMap(
          _.emails.find(_.id == email.get(AFFILIATION).value))

      def parse_affil(affil: Params.Data, authors: Authors): Option[Affiliation] =
        authors.get(affil.get(ID).value).flatMap { author =>
          val id = affil.get(AFFILIATION).value
          if (id.isEmpty) Some(Unaffiliated(author.id))
          else (author.emails ++ author.homepages).collectFirst {
            case e: Email if e.id == id => e
            case h: Homepage if h.id == id => h
          }
        }

      def parse_related(related: Params.Data, references: List[Reference]): Option[Reference] =
        Model.Related.from_string(related.get(KIND).value).flatMap(
          Model.validate_related(_, related.get(INPUT).value, references).opt)

      def parse_new_author(author: Params.Data, authors: Authors): Option[Author] =
        Model.validate_new_author(
          author.get(ID).value, author.get(NAME).value, author.get(ORCID).value, authors).opt

      def parse_new_affil(affil: Params.Data, authors: Authors): Option[Affiliation] =
        authors.get(affil.get(AUTHOR).value).flatMap(author =>
          Model.validate_new_affil(affil.get(ID).value, affil.get(AFFILIATION).value, author).opt)

      def parse_entry(entry: Params.Data, authors: Authors): Option[Model.Create_Entry] =
        for {
          topics <-
            fold(entry.list(TOPIC), List.empty[Topic]) {
              case (topic, topics) => parse_topic(topic, topics).map(topics :+ _)
            }
          affils <-
            fold(entry.list(AUTHOR), List.empty[Affiliation]) {
              case (affil, affils) => parse_affil(affil, authors).map(affils :+ _)
            }
          notifies <-
            fold(entry.list(NOTIFY), List.empty[Email]) {
              case (email, emails) => parse_email(email, authors).map(emails :+ _)
            }
          related <-
            fold(entry.list(RELATED), List.empty[Reference]) {
              case (related, references) => parse_related(related, references).map(references :+ _)
            }
          license <- state.licenses.get(entry.get(LICENSE).value)
        } yield Model.Create_Entry(
          name = Val.ok(entry.get(NAME).value),
          title = Val.ok(entry.get(TITLE).value),
          topics = Val.ok(topics),
          topic_input = parse_topic(entry.get(TOPIC), Nil),
          license = license,
          `abstract` = Val.ok(entry.get(ABSTRACT).value),
          author_input = authors.get(entry.get(AUTHOR).value),
          notify_input = authors.get(entry.get(NOTIFY).value),
          affils = Val.ok(affils),
          notifies = Val.ok(notifies),
          related = related,
          related_kind = Model.Related.from_string(entry.get(RELATED).get(KIND).value),
          related_input = Val.ok(entry.get(RELATED).get(INPUT).value))

      for {
        (new_author_ids, all_authors) <-
          fold(params.list(AUTHOR), (List.empty[Author.ID], state.authors)) {
            case (author, (new_authors, authors)) =>
              parse_new_author(author, authors).map(author =>
                (new_authors :+ author.id, authors.updated(author.id, author)))
          }
        (new_affils, all_authors) <-
          fold(params.list(AFFILIATION), (List.empty[Affiliation], all_authors)) {
            case (affil, (new_affils, authors)) =>
              parse_new_affil(affil, authors).map { affil =>
                val author = authors(affil.author)
                (new_affils :+ affil, affil match {
                  case _: Unaffiliated => authors
                  case e: Email =>
                    authors.updated(author.id, author.copy(emails = author.emails :+ e))
                  case h: Homepage =>
                    authors.updated(author.id, author.copy(homepages = author.homepages :+ h))
                })
              }
          }
        new_authors = new_author_ids.map(all_authors)
        entries <- fold(params.list(ENTRY), List.empty[Model.Create_Entry]) {
          case (entry, entries) => parse_entry(entry, all_authors).map(entries :+ _)
        }
      } yield Model.Create(
        entries = Val.ok(entries),
        new_authors = Val.ok(new_authors),
        new_author_input = params.get(AUTHOR).get(NAME).value,
        new_author_orcid = params.get(AUTHOR).get(ORCID).value,
        new_affils = Val.ok(new_affils),
        new_affils_author = all_authors.get(params.get(AFFILIATION).value),
        new_affils_input = params.get(AFFILIATION).get(ADDRESS).value)
    }

    def parse_submission_list(params: Params.Data): Option[Model.Submission_List] = {
      def parse_overview(entry: Params.Data): Option[Model.Overview] =
        for {
          date <-
            Exn.capture(LocalDate.parse(entry.get(DATE).value)) match {
              case Exn.Res(date) => Some(date)
              case Exn.Exn(_) => None
            }
          status <- Model.Status.from_string(entry.get(STATUS).value)
        } yield Model.Overview(entry.get(ID).value, date, entry.get(NAME).value, status)

      val submissions =
        fold(params.list(ENTRY), List.empty[Model.Overview]) {
          case (entry, overviews) => parse_overview(entry).map(overviews :+ _)
        }
      submissions.map(Model.Submission_List.apply)
    }

    def action(params: Params.Data): Params.Key = params.get(Web_App.ACTION).value

    def parse_num_entry(action: Params.Key): Option[(Int, Params.Key)] =
      List_Key.split(ENTRY, action).map(_.swap)

    def parse_num_affil(action: Params.Key): Option[(Int, Params.Key)] =
      List_Key.split(AUTHOR, action).map(_.swap)

    def parse_num_notify(action: Params.Key): Option[(Int, Params.Key)] =
      List_Key.split(NOTIFY, action).map(_.swap)

    def parse_num_topic(action: Params.Key): Option[(Int, Params.Key)] =
      List_Key.split(TOPIC, action).map(_.swap)

    def parse_num_related(action: Params.Key): Option[(Int, Params.Key)] =
      List_Key.split(RELATED, action).map(_.swap)

    def parse_num_new_affil(action: Params.Key): Option[(Int, Params.Key)] =
      List_Key.split(AFFILIATION, action).map(_.swap)

    def parse_id(props: Properties.T): Option[String] = Properties.get(props, ID)

    def parse_message(params: Params.Data): String = params.get(MESSAGE).value

    def parse_archive_file(params: Params.Data): String = params.get(ARCHIVE).get(FILE).value

    def parse_archive_filename(params: Params.Data): String = params.get(ARCHIVE).value
  }


  /* server */

  object State {
    def load(afp: AFP_Structure): State = {
      val authors = afp.load_authors
      val topics = afp.load_topics
      val licenses = afp.load_licenses
      val releases = afp.load_releases
      val entries = afp.load_entries(authors, topics, licenses, releases)

      State(authors, topics, licenses, releases, entries)
    }
  }

  case class State(
    authors: Authors,
    topics: Topics,
    licenses: Licenses,
    releases: Releases,
    entries: Entries)

  object Mode extends Enumeration {
    val EDIT, SUBMISSION = Value
  }

  class Server(
    paths: Web_App.Paths,
    afp: AFP_Structure,
    mode: Mode.Value,
    handler: Handler,
    devel: Boolean,
    verbose: Boolean,
    progress: Progress,
    port: Int
  ) extends Web_App.Server[Model.T](paths, port, verbose, progress) {
    private var _state: State = State.load(afp)

    val repo = Mercurial.the_repository(afp.base_dir)

    val view = new View(paths, mode)

    def render(model: Model.T): XML.Body =
      model match {
        case create: Model.Create => view.render_create(create, _state)
        case upload: Model.Upload => view.render_upload(upload, _state)
        case submission: Model.Submission => view.render_submission(submission, _state)
        case submissions: Model.Submission_List => view.render_submission_list(submissions)
        case created: Model.Created => view.render_created(created)
        case Model.Invalid => view.render_invalid
      }


    /* control */

    def add_entry(params: Params.Data): Option[Model.T] =
      for (model <- view.parse_create(params, _state)) yield model.add_entry(_state)

    def remove_entry(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_entry, _) <- view.parse_num_entry(view.action(params))
      } yield model.remove_entry(num_entry)

    def add_author(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_entry, _) <- view.parse_num_entry(view.action(params))
        entry <- model.entries.v.unapply(num_entry)
      } yield model.update_entry(num_entry, entry.add_affil)

    def remove_author(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_affil, action) <- view.parse_num_affil(view.action(params))
        (num_entry, _) <- view.parse_num_entry(action)
        entry <- model.entries.v.unapply(num_entry)
        affil <- entry.affils.v.unapply(num_affil)
      } yield model.update_entry(num_entry, entry.remove_affil(affil))

    def add_notify(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_entry, _) <- view.parse_num_entry(view.action(params))
        entry <- model.entries.v.unapply(num_entry)
        entry1 <- entry.add_notify
      } yield model.update_entry(num_entry, entry1)

    def remove_notify(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_notify, action) <- view.parse_num_notify(view.action(params))
        (num_entry, _) <- view.parse_num_entry(action)
        entry <- model.entries.v.unapply(num_entry)
        notify <- entry.notifies.v.unapply(num_notify)
      } yield model.update_entry(num_entry, entry.remove_notify(notify))

    def add_topic(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_entry, _) <- view.parse_num_entry(view.action(params))
        entry <- model.entries.v.unapply(num_entry)
      } yield model.update_entry(num_entry, entry.add_topic(_state))

    def remove_topic(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_topic, action) <- view.parse_num_topic(view.action(params))
        (num_entry, _) <- view.parse_num_entry(action)
        entry <- model.entries.v.unapply(num_entry)
        topic <- entry.topics.v.unapply(num_topic)
      } yield model.update_entry(num_entry, entry.remove_topic(topic))

    def add_related(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_entry, _) <- view.parse_num_entry(view.action(params))
        entry <- model.entries.v.unapply(num_entry)
      } yield model.update_entry(num_entry, entry.add_related)

    def remove_related(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_related, action) <- view.parse_num_related(view.action(params))
        (num_entry, _) <- view.parse_num_entry(action)
        entry <- model.entries.v.unapply(num_entry)
        related <- entry.related.unapply(num_related)
      } yield model.update_entry(num_entry, entry.remove_related(related))

    def add_new_author(params: Params.Data): Option[Model.T] =
      for (model <- view.parse_create(params, _state)) yield model.add_new_author(_state)

    def remove_new_author(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_author, _) <- view.parse_num_affil(view.action(params))
        author <- model.new_authors.v.unapply(num_author)
        model1 <- model.remove_new_author(author)
      } yield model1

    def add_new_affil(params: Params.Data): Option[Model.T] =
      for (model <- view.parse_create(params, _state)) yield model.add_new_affil

    def remove_new_affil(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        (num_affil, _) <- view.parse_num_new_affil(view.action(params))
        affil <- model.new_affils.v.unapply(num_affil)
        model1 <- model.remove_new_affil(affil)
      } yield model1

    def upload(params: Params.Data): Option[Model.T] =
      for (model <- view.parse_create(params, _state))
      yield model.validate(_state, view.parse_message(params), mode == Mode.EDIT)

    def create(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_create(params, _state)
        upload <-
          model.validate(_state, view.parse_message(params), mode == Mode.EDIT) match {
            case upload: Model.Upload => Some(upload)
            case _ => None
          }
      } yield synchronized {
        val (model1, state) =
          mode match {
            case Mode.EDIT => upload.save(handler, _state)
            case Mode.SUBMISSION =>
              upload.submit(
                handler, view.parse_archive_file(params),
                view.parse_archive_filename(params), _state)
          }
        _state = state
        model1
      }

    def empty_submission: Option[Model.T] = Some(Model.Create.init(_state))

    def get_submission(props: Properties.T): Option[Model.Submission] =
      for {
        id <- view.parse_id(props)
        submission <- handler.get(id, _state)
      } yield submission

    def resubmit(params: Params.Data): Option[Model.T] =
      for (submission <- handler.get(view.action(params), _state)) yield Model.Upload(submission)

    def submit(params: Params.Data): Option[Model.T] =
      for {
        submission <- handler.get(view.action(params), _state)
        submission1 <- submission.submit(handler)
      } yield submission1

    def abort_build(params: Params.Data): Option[Model.T] =
      for {
        submission <- handler.get(view.action(params), _state)
        submission1 <- submission.abort_build(handler)
      } yield submission1

    def submission(params: Params.Data): Option[Model.T] = view.parse_create(params, _state)

    def set_status(params: Params.Data): Option[Model.T] =
      for {
        model <- view.parse_submission_list(params)
        (num_entry, _) <- view.parse_num_entry(view.action(params))
        entry <- model.submissions.unapply(num_entry)
      } yield synchronized {
        if (!devel) {
          val updated = entry.update_repo(repo)
          if (updated) {
            progress.echo_if(verbose, "Updating server data...")
            _state = State.load(afp)
            progress.echo("Updated repo to " + repo.id())
          }
        }
        model
      }

    def submission_list: Option[Model.T] = Some(handler.list(_state))

    def download(props: Properties.T): Option[Path] =
      for {
        id <- view.parse_id(props)
        patch <- handler.get_patch(id)
      } yield patch

    def download_archive(props: Properties.T): Option[Path] =
      for {
        id <- view.parse_id(props)
        archive <- handler.get_archive(id)
      } yield archive

    def style_sheet: Option[Path] = Some(afp.base_dir + Path.make(List("tools", "main.css")))

    val error_model = Model.Invalid

    val endpoints = List(
      Get(Page.SUBMIT, "empty submission form", _ => empty_submission),
      Get(Page.SUBMISSION, "get submission", get_submission),
      Get(Page.SUBMISSIONS, "list submissions", _ => submission_list),
      Get_File(API.SUBMISSION_DOWNLOAD, "download patch", download),
      Get_File(API.SUBMISSION_DOWNLOAD_ZIP, "download archive", download_archive),
      Get_File(API.SUBMISSION_DOWNLOAD_TGZ, "download archive", download_archive),
      Get_File(API.CSS, "download css", _ => style_sheet),
      Post(API.RESUBMIT, "get form for resubmit", resubmit),
      Post(API.SUBMIT, "submit to editors", submit),
      Post(API.BUILD_ABORT, "abort the build", abort_build),
      Post(API.SUBMISSION, "get submission form", submission),
      Post(API.SUBMISSION_AUTHORS_ADD, "add author", add_new_author),
      Post(API.SUBMISSION_AUTHORS_REMOVE, "remove author", remove_new_author),
      Post(API.SUBMISSION_AFFILIATIONS_ADD, "add affil", add_new_affil),
      Post(API.SUBMISSION_AFFILIATIONS_REMOVE, "remove affil", remove_new_affil),
      Post(API.SUBMISSION_ENTRIES_ADD, "add entry", add_entry),
      Post(API.SUBMISSION_ENTRIES_REMOVE, "remove entry", remove_entry),
      Post(API.SUBMISSION_ENTRY_AUTHORS_ADD, "add entry author", add_author),
      Post(API.SUBMISSION_ENTRY_AUTHORS_REMOVE, "remove entry author", remove_author),
      Post(API.SUBMISSION_ENTRY_NOTIFIES_ADD, "add notify", add_notify),
      Post(API.SUBMISSION_ENTRY_NOTIFIES_REMOVE, "remove notify", remove_notify),
      Post(API.SUBMISSION_ENTRY_TOPICS_ADD, "add topic", add_topic),
      Post(API.SUBMISSION_ENTRY_TOPICS_REMOVE, "remove topic", remove_topic),
      Post(API.SUBMISSION_ENTRY_RELATED_ADD, "add related", add_related),
      Post(API.SUBMISSION_ENTRY_RELATED_REMOVE, "remove related", remove_related),
      Post(API.SUBMISSION_UPLOAD, "upload archive", upload),
      Post(API.SUBMISSION_CREATE, "create submission", create),
      Post(API.SUBMISSION_STATUS, "set submission status", set_status))

    val head = view.render_head
  }


  /* Isabelle tool wrapper */

  val isabelle_tool = Isabelle_Tool("afp_submit", "start afp submission server",
    Scala_Project.here,
    { args =>

      var backend_path = Path.current
      var frontend = "http://localhost"
      var devel = false
      var verbose = false
      var port = 8080
      var dir: Option[Path] = None

      val getopts = Getopts("""
Usage: isabelle afp_submit [OPTIONS]

  Options are:
      -a PATH      backend path (if endpoint is not server root)
      -b URL       application frontend url. Default: """ + frontend + """
      -d           devel mode (serves frontend and skips automatic AFP repository updates)
      -p PORT      server port. Default: """ + port + """
      -v           verbose
      -D DIR       submission directory

  Start afp submission server. Server is in "edit" mode
  unless directory to store submissions in is specified.
""",
        "a:" -> (arg => backend_path = Path.explode(arg)),
        "b:" -> (arg => frontend = arg),
        "d" -> (_ => devel = true),
        "p:" -> (arg => port = Value.Int.parse(arg)),
        "v" -> (_ => verbose = true),
        "D:" -> (arg => dir = Some(Path.explode(arg))))

      getopts(args)

      val afp = AFP_Structure()

      val progress = new Console_Progress(verbose = verbose)

      val (handler, mode) = dir match {
        case Some(dir) => (Handler.Adapter(dir, afp), Mode.SUBMISSION)
        case None => (Handler.Edit(afp), Mode.EDIT)
      }

      val paths = Web_App.Paths(Url(frontend + ":" + port), backend_path, serve_frontend = devel,
        landing = Page.SUBMISSIONS)
      val server = new Server(paths = paths, afp = afp, mode = mode,
        handler = handler, devel = devel, verbose = verbose, progress = progress, port = port)

      server.run()
    })
}
