package org.serendipity.restapi.database.seed;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.serendipity.restapi.entity.*;
import org.serendipity.restapi.repository.AddressRepository;
import org.serendipity.restapi.repository.IndividualRepository;
import org.serendipity.restapi.repository.OrganisationRepository;
import org.serendipity.restapi.repository.RoleRepository;
import org.serendipity.restapi.type.LocationType;
import org.serendipity.restapi.type.PartyType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Timestamp;
import java.util.HashSet;

@Component
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
public class AustralianSenators implements CommandLineRunner {

  static final String PATH = "sample-data/allsenel.csv";

  static final int TITLE = 0;
  static final int SALUTATION = 1;
  static final int SURNAME = 2;
  static final int FIRST_NAME = 3;
  static final int OTHER_NAME = 4;
  static final int PREFERRED_NAME = 5;
  static final int INITIALS = 6;
  static final int POST_NOMINALS = 7;
  static final int POLITICAL_PARTY = 9;
  static final int GENDER = 10;

  @Autowired
  private AddressRepository addressRepository;
  
  @Autowired
  private IndividualRepository individualRepository;

  @Autowired
  private OrganisationRepository organisationRepository;

  @Autowired
  private RoleRepository roleRepository;

  @Override
  @Transactional
  public void run(String... args) throws Exception {

    log.info("Loading Australian Senators ...");

    try {

      //
      // Parliament House Address
      //

      Timestamp currentTime = new Timestamp(System.currentTimeMillis());

      Location location = Location.builder()
          .type(LocationType.ADDRESS)
          .displayName("PO Box 6100 Parliament House Canberra ACT 2600")
          .fromDate(currentTime)
          .build();

      Address parliamentHouse = Address.builder()
          .location(location)
          .name("The Senate")
          .line1("PO Box 6100")
          .line2("Parliament House")
          .city("Canberra")
          .state("ACT")
          .postalCode("2600")
          .country("Australia")
          .addressType("Mailing")
          .build();

      addressRepository.save(parliamentHouse);

      InputStream resource = new ClassPathResource(PATH).getInputStream();

      BufferedReader buffer = new BufferedReader(new InputStreamReader(resource));

      String line = buffer.readLine();

      log.info("Header: {}", line);

      while ((line = buffer.readLine()) != null && !line.isEmpty()) {

        // Note: No support for strings with embedded comma's, for example: "Commonwealth Parliament Offices, Suite 8"
        String[] fields = line.split(",");

        String displayName = fields[SURNAME] + ", " + fields[TITLE] + " " + fields[FIRST_NAME];

        Party individualParty = Party.builder()
            .type(PartyType.INDIVIDUAL)
            .displayName(displayName)
            .addresses(new HashSet<Address>())
            .roles(new HashSet<Role>())
            .build();

        String email = fields[FIRST_NAME].toLowerCase() + "." + fields[SURNAME].toLowerCase() + "@aph.gov.au";

        Individual individual = Individual.builder()
            .party(individualParty)
            .title(fields[TITLE])
            .givenName(fields[FIRST_NAME])
            .middleName(fields[OTHER_NAME])
            .familyName(fields[SURNAME])
            .honorific(fields[POST_NOMINALS])
            .salutation(fields[SALUTATION])
            .preferredName(fields[PREFERRED_NAME])
            .initials(fields[INITIALS])
            .gender(fields[GENDER])
            .email(email)
            .phoneNumber("")
            .photoUrl("")
            .build();

        individualRepository.save(individual);

        Role role = Role.builder()
            .role("Member")
            .partyId(individual.getParty().getId())
            .partyType(individual.getParty().getType())
            .partyName(individual.getParty().getDisplayName())
            .partyEmail(individual.getEmail())
            .partyPhoneNumber(individual.getPhoneNumber())
            .relationship("Membership")
            .reciprocalRole("Organisation")
            // .reciprocalPartyId(1L)
            // .reciprocalPartyType(PartyType.ORGANISATION)
            // .reciprocalPartyName("")
            // .reciprocalPartyEmail("")
            // .reciprocalPartyPhoneNumber("")
            .build();

        boolean membership = true;

        // "AG" | "ALP" | "CA" | "JLN" | "LP" | "NATS" | "PHON" | "IND
        String abbreviation = fields[POLITICAL_PARTY].toUpperCase();

        AustralianPoliticalParty politicalParty = AustralianPoliticalParty.valueOfAbbreviation(abbreviation);

        switch (politicalParty) {

          case AUSTRALIAN_GREENS:
          case AUSTRALIAN_LABOR_PARTY:
          case CENTRE_ALLIANCE:
          case JACQUI_LAMBIE_NETWORK:
          case LIBERAL_PARTY_OF_AUSTRALIA:
          case NATIONAL_PARTY_OF_AUSTRALIA:
          case PAULINE_HANSONS_ONE_NATION:

            log.info("Political Party: {}", politicalParty.toString());

            Pageable pageable = PageRequest.of(0, 1);

            Page<Organisation> organisations = organisationRepository.findByName(politicalParty.toString(), pageable);

            Organisation organisation = organisations.getContent().get(0);

            try {

              ObjectMapper mapper = new ObjectMapper();

              mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
              mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

              mapper.enable(SerializationFeature.INDENT_OUTPUT);

              log.info("{}", "\n" + mapper.writeValueAsString(organisation));

            } catch (JsonProcessingException jpe) {

              log.error("Australian Senators - JSON Processing Exception");
            }

            role.setReciprocalPartyId(organisation.getParty().getId());
            role.setReciprocalPartyType(organisation.getParty().getType());
            role.setReciprocalPartyName(organisation.getParty().getDisplayName());
            role.setReciprocalPartyEmail(organisation.getEmail());
            role.setReciprocalPartyPhoneNumber(organisation.getPhoneNumber());

            break;

          case INDEPENDENT:
          default:

            log.info("Political Party: {}", abbreviation);

            membership = false;

            break;
        }

        individualParty.getAddresses().add(parliamentHouse);

        if (membership) {
          roleRepository.save(role);
          individualParty.getRoles().add(role);
        }

        individualRepository.save(individual);

      }

      buffer.close();

      log.info("Loading Australian Senators complete");

    } catch (IOException | NullPointerException e) {

      log.error("{}", e.getLocalizedMessage());
    }

  }
  
}

// https://stackoverflow.com/questions/10387329/using-string-representations-of-enum-values-in-switch-case
// https://www.baeldung.com/java-enum-values

// static final int STATE = 8;
// static final int ELECTORATE_TELEPHONE = 16;

// Set<Address> addresses = new HashSet<Address>();
// addresses.add(parliamentHouse);

/*

import java.text.SimpleDateFormat;
import java.util.Date;

Date dateOfBirth = new SimpleDateFormat("dd/MM/yyyy").parse("01/11/1982");
.dateOfBirth(dateOfBirth)

*/


/*

    // Auditable auditable = new Auditable();

    Location location = new Location();
    location.setType("Address");
    location.setFromDate(timestamp);
    location.setDisplayName("PO Box 6100 Parliament House Canberra ACT 2600");
  
*/
