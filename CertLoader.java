Please verify this event bridge approach, 
 
we will verify the secret manager version changes instead of expiry date in the source code to read the values from Secret Manager.
Use Event bridge to reload the configuration from Lambda in warm state
Cost is negligible only as it will occur only once for every 2 years. 
 
Secrets Manager

      |

      | Certificate Updated

      v

EventBridge Rule

      |

      v

Refresh Controller Lambda

      |

      +----------------------------+

      |                            |

      v                            v

Update Lambda A             Update Lambda B

      |                            |

      +------------+---------------+

                   |

                   v

New Execution Environments

                   |

                   v

Read New Certificate
 
