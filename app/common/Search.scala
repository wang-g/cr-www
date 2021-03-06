package common

import play.api.data._
import play.api.data.Forms._
import play.api.data.format._
import play.api.db.slick._
import play.api.db.slick.Config.driver.simple._
import play.api.db.slick.Config.driver.simple.{Session => DBSession}
import models.Tables._
import common.Review._

object Search {

   /**
    * booleanIntFormat is a formatter that accepts 1 and 0 as true and
    * false respectively.
    */
   def booleanIntFormat: Formatter[Boolean] = new Formatter[Boolean] {
     override val format = Some(("format.boolean", Nil))
     def bind(key: String, data: Map[String, String]) = {
       Right(data.get(key).getOrElse("false")).right.flatMap {
         case "true" | "1" => Right(true)
         case "false" | "0" => Right(false)
         case _ => Left(Seq(FormError(key, "error.boolean", Nil)))
       }
     }
     def unbind(key: String, value: Boolean) = Map(key -> value.toString)
   }

  case class SearchRequest(quickSearch: Option[String], courseCode: Option[String], professor: Option[String], departments: List[String], title: Option[String], fall: Option[Boolean], spring: Option[Boolean], recent: Option[Boolean])

  val form = Form(mapping(
    "q" -> optional(text),
    "course_code" -> optional(text),
    "professor" -> optional(text),
    "department" -> Forms.list(text),
    "title" -> optional(text),
    "fall" -> optional(of[Boolean](booleanIntFormat)),
    "spring" -> optional(of[Boolean](booleanIntFormat)),
    "attribute_recent" -> optional(of[Boolean](booleanIntFormat))
  )(SearchRequest.apply)(SearchRequest.unapply))

  def filterDups(revs: List[CrReview2008Row]) = {
    var previousDept = ""
    var previousCNum = ""
    revs.flatMap({ course =>
      val dept = course(2)
      val cnum = course(3)
      if (dept == previousDept && cnum == previousCNum) {
        None
      } else {
        previousDept = dept
        previousCNum = cnum
        Some(course)
      }
    })
  }

  type ReviewQuery = Query[CrReview2008,CrReview2008#TableElementType,Seq]

  /**
   * Sorting
   * Sort first by department code and course num so reviews for the same
   * course are grouped. Then, prioritize reviews for the same course
   * as follows:
   *  1. We want actually published reviews first (sort DESC by active)
   *  2. We want reviews with sufficient information (sort ASC by insufficient)
   *  3. We want recent reviews (sort DESC by edition)
   *  4. We want reviews with lots of respondents (sort DESC by num_respondents)
   */
  def sortSearch(query: ReviewQuery) = query.sortBy(rev =>
      (rev.departmentCode.asc, rev.courseNum.asc, rev.active.desc, rev.insufficient.asc, rev.edition.desc, rev.numRespondents.desc)
  )

  val searchCourses =
    CrReview2008.filter(rev => rev.revision === 0 && rev.edition <= Global.current_edition)

  def searchByDepartmentRaw(query: ReviewQuery, dept: Column[String]) =
    query.filter(_.departmentCode === dept)

  val searchByDepartment = Compiled { dept: Column[String] =>
      sortSearch(searchByDepartmentRaw(searchCourses, dept))
  }

  def sortFetch(query: ReviewQuery) = query.sortBy(r =>
    (r.active.desc, r.insufficient.asc, r.edition.desc, r.numRespondents.desc)
  )

  def searchByCourseRaw(query: ReviewQuery, dept: Column[String], num: Column[String]) =
    searchByDepartmentRaw(query, dept).filter(_.courseNum === num)

  val getCourse = Compiled { (dept: Column[String], num: Column[String]) =>
    sortFetch(searchByCourseRaw(searchCourses, dept, num)).take(1)
  }

  def searchCourseByEditionRaw(query: ReviewQuery, dept: Column[String], num: Column[String], edition: Column[String]) =
    searchByCourseRaw(query, dept, num).filter(_.edition === edition)

  val getCourseByEdition = Compiled { (dept: Column[String], num: Column[String], edition: Column[String]) =>
    sortFetch(searchCourseByEditionRaw(searchCourses, dept, num, edition)).take(1)
  }

  def searchSpecificCourse(query: ReviewQuery, dept: Column[String], num: Column[String], edition: Column[String], section: Column[String]) =
    searchCourseByEditionRaw(query, dept, num, edition).filter(_.section === section)

  val getSpecificCourse = Compiled { (dept: Column[String], num: Column[String], edition: Column[String], section: Column[String]) =>
    sortFetch(searchSpecificCourse(searchCourses, dept, num, edition, section)).take(1)
  }

  def process(sr: SearchRequest)(implicit session: DBSession) = {
    val checkDept = if (sr.departments.length > 0) {
      for {
        rev <- searchCourses
        if rev.departmentCode inSetBind sr.departments
      } yield rev
    } else searchCourses

    val checkCode = sr.courseCode match {
      case Some(code) => checkDept.filter(_.courseNum === code)
      case None => checkDept
    }

    val checkProf = sr.professor match {
      case Some(prof) => checkCode.filter(_.professor.toLowerCase like f"%%$prof%s%%")
      case None => checkCode
    }

    val checkTitle = sr.title match {
      case Some(title) => checkProf.filter(_.title.toLowerCase like f"%%$title%s%%")
      case None => checkProf
    }

    val sorted = sortSearch(checkTitle)

    filterDups(sorted.list)
  }

  def getBannerCode = Compiled { dept: Column[String] =>
    CrDepartment2005
    .filter(rev => rev.shortCode === dept || rev.bannerCode === dept)
    .map(_.bannerCode)
    .take(1)
  }

  /**
   * Takes a list of department codes in either short (e.g. CS) or banner
   * (e.g. CSCI) form and returns a list of the same departments' banner codes
   */
  def getBannerCodes(depts: Traversable[String])(implicit session: DBSession) =
    CrDepartment2005
    .filter(rev => (rev.shortCode inSetBind depts) || (rev.bannerCode inSetBind depts))
    .map(_.bannerCode)
    .list

  def cleanForLike(str: String) = str.replaceAll("%", "[%]").toLowerCase

  def extractDepts(sr: SearchRequest)(implicit session: DBSession) = {
    (sr.courseCode, sr.quickSearch) match {
      case (Some(deptRegex(depts, _)), _) =>
        val newDepts = sr.departments ++ getBannerCodes(depts.split(", "))
        sr.copy(courseCode = None, departments = newDepts)
      case (_, Some(deptRegex(depts, _))) =>
        val newDepts = sr.departments ++ getBannerCodes(depts.split(", "))
        sr.copy(quickSearch = None, departments = newDepts)
      case _ =>
        sr
    }
  }

  def extractProfs(sr: SearchRequest)(implicit session: DBSession) = {
    (sr.professor, sr.quickSearch) match {
      case (None, Some(prof)) => {
        val newProf = cleanForLike(prof)
        val matching = CrReview2008
        .filter(_.professor.toLowerCase like f"%%$newProf%s%%")
        .length
        .run
        if (matching > 0) {
          sr.copy(professor = Some(newProf), quickSearch = None)
        } else sr
      }
      case _ => sr
    }
  }

  def extractTitle(sr: SearchRequest) = {
    (sr.title, sr.quickSearch) match {
      case (Some(title), _) =>
        val newTitle = cleanForLike(title)
        sr.copy(title = Some(newTitle))
      case (None, Some(title)) =>
        val newTitle = cleanForLike(title)
        sr.copy(title = Some(newTitle), quickSearch = None)
      case _ => sr
    }
  }

  def extractAll(sr: SearchRequest)(implicit session: DBSession) =
    extractTitle(extractProfs(extractDepts(sr)))

}
