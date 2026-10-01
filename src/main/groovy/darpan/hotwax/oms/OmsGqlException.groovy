package darpan.hotwax.oms

/** A GraphQL or transport failure carrying the stable `extensions.code` when there was one. */
class OmsGqlException extends RuntimeException {
    final String code
    OmsGqlException(String code, String message) {
        super(message)
        this.code = code
    }
}
