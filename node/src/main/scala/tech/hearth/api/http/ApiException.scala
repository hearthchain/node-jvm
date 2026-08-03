package tech.hearth.api.http

case class ApiException(apiError: ApiError) extends Exception(apiError.message)
